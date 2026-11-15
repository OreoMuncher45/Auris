@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.music.bitchord.playback.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.ListenerVolumePolicy
import com.music.bitchord.playback.audio.MediaVolumeProbe
import com.music.bitchord.playback.audio.PrecisionAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct USB session around the decent-player usbdevfs driver (MIT), ported
 * from LastWave's implementation of the same feature.
 *
 * PCM goes to isochronous URBs. AudioFlinger never sees the stream.
 *
 * The listener's level is applied here, because nothing downstream of the
 * phone's own mixer is left to apply it: the DAC's own control takes it as
 * an offset from the level the DAC is already set to, and a DAC without a
 * usable control gets the samples scaled instead. Either way the DAC's knob
 * stays where the listener put it.
 *
 * The gold clock check is the DAC GET_CUR rate, not a successful open.
 */
class ExclusiveUsbOutput(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var wanted = false
    @Volatile private var active = false
    @Volatile private var clockMatched = false
    @Volatile private var hardwareVolume = false
    @Volatile private var configuredRateHz = 0
    @Volatile private var configuredChannelCount = 0
    @Volatile private var softwareGainValue = 1f
    @Volatile private var lastHardwareRate = 0

    /**
     * The listener's own level, 0..1, as the app sees it.
     *
     * Not the system's media-stream volume: Android pins that at maximum
     * when a USB DAC attaches, so it is not a reading of anything the
     * listener chose. This is an *offset* from the DAC's own level, never an
     * absolute one — see [applyVolumeLocked] and
     * [com.music.bitchord.playback.audio.ListenerVolumePolicy].
     */
    @Volatile private var listenerLevel: Float =
        AppSettings.playerVolume.value.coerceIn(0f, 1f)

    /** The level the DAC is set to, in dB, before the app's offset is added. */
    private var dacBaseDb: Double = 0.0
    private var dacBaseKnown = false

    /** The level the app last wrote to the DAC, in dB. */
    @Volatile private var lastWrittenDb: Double? = null

    /** What the DAC last read back, in dB. */
    @Volatile private var dacCurrentDb: Double? = null

    /**
     * Set when the listener's level has changed and has not been applied to
     * the DAC yet.
     *
     * The application is a USB control transfer, so it waits for the writer
     * thread rather than happening on the audio thread that reports the
     * change.
     */
    @Volatile private var volumeDirty = false

    /** When the DAC's own level was last re-read. */
    @Volatile private var lastDacLevelCheckMs = 0L

    private val usbAudio = UsbAudioDevice.getInstance(appContext)
    private var stream: UsbAudioStream? = null
    private val pcmLock = Object()
    private val pcmQueue = ArrayDeque<QueuedPcm>()
    @Volatile private var queuedBytes = 0
    @Volatile private var flushRequested = false
    @Volatile private var writerStop = false
    private var writer: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var sourceEncoding = 0
    private var activeWireBits = 0
    private var useFloatWrite = false
    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startMediaTimeNeedsInit = true
    @Volatile private var mediaTimeBaseFrames = 0L
    @Volatile private var lastWriteElapsedMs = 0L
    @Volatile private var writingInProgress = false
    @Volatile private var paused = false
    private var featureVolume: UacFeatureVolume? = null
    /** True when the DAC answered GET_CUR for its clock during this session. */
    @Volatile private var clockReadbackSupported = false
    @Volatile private var lastClockCheckMs = 0L
    private var volumeReceiverRegistered = false
    /** Last exclusive open failure, for the signal-path dialog. Cleared on
     *  the next successful start so a stale reason never shows. */
    @Volatile var lastFailureReason: String? = null
        private set
    /** True when retrying the open could plausibly succeed (device/permission). */
    @Volatile private var lastFailureRetryable = false
    private var volumeProbed = false
    /** Fork setting: never let the DAC's Feature Unit own the listening level. */
    @Volatile private var forceSoftwareVolume = false

    private val sessionBlockedDevices = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var currentDeviceKey: String? = null
    @Volatile private var sessionSummary: String = ""

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val volumePrefs =
        appContext.getSharedPreferences("bitchord_exclusive_usb", Context.MODE_PRIVATE)

    init {
        clearBlockedDevices()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (streamType != AudioManager.STREAM_MUSIC) return
            syncSystemVolume()
        }
    }

    fun setWanted(enabled: Boolean) {
        synchronized(lock) {
            if (wanted == enabled) {
                if (enabled) {
                    ensureVolumeObserverLocked()
                    syncSystemVolume()
                }
                return
            }
            wanted = enabled
            if (!enabled) {
                teardownLocked(closeDevice = true)
            } else {
                clearBlockedDevices()
                ensureVolumeObserverLocked()
                syncSystemVolume()
            }
        }
    }

    fun isWanted(): Boolean = wanted

    /**
     * True when an exclusive open has something to try right now: a DAC is
     * attached, it has granted access, and a failed test has not disabled it.
     * Callers use this to avoid tearing down the working AudioTrack path for
     * an attempt that cannot get past the permission check.
     */
    fun canOpen(): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return false
        if (!manager.hasPermission(device)) return false
        return blockReasonFor(device.vendorId, device.productId) == null
    }

    /**
     * Software (PCM-scaled) volume instead of the DAC's hardware Feature Unit.
     *
     * Switching to software puts the DAC's own level back first, so the two
     * attenuations never stack — and so the DAC is not left louder than the
     * listener's knob says. Switching back takes effect at the next start.
     */
    fun setForceSoftwareVolume(value: Boolean) {
        synchronized(lock) {
            if (forceSoftwareVolume == value) return
            forceSoftwareVolume = value
            if (value && active && hardwareVolume) {
                // Undo the app's own offset and leave the DAC where the
                // listener's knob had it. Parking the control at 0 dB would
                // raise the level above that knob, which is the opposite of
                // what "use software instead" is meant to do.
                val base = if (dacBaseKnown) dacBaseDb else null
                if (base != null) {
                    runCatching { featureVolume?.setDb(base) }
                    lastWrittenDb = base
                }
                hardwareVolume = false
                applyVolumeLocked()
                Log.i(TAG, "volume forced to software; the DAC's own level left alone")
            }
        }
    }

    fun isForceSoftwareVolume(): Boolean = forceSoftwareVolume

    /** True when the attached DAC exposes a usable hardware volume (even if not used). */
    fun hasHardwareVolumeControl(): Boolean = active && featureVolume != null && (hardwareVolume || featureVolumeAttached)

    @Volatile private var featureVolumeAttached = false

    fun isActive(): Boolean = active

    fun isClockMatched(): Boolean = active && clockMatched

    /** Whether the last open failure is worth retrying at the next track. */
    fun failureIsRetryable(): Boolean = lastFailureRetryable

    fun usesHardwareVolume(): Boolean = active && hardwareVolume

    /**
     * PCM software gain for exclusive output. 1 when the DAC's own control
     * carries the level; otherwise the listener's level as a sample gain.
     */
    fun softwareGain(): Float = if (!active || (hardwareVolume && !forceSoftwareVolume)) 1f else softwareGainValue

    /**
     * The absolute level the DAC's own volume control is at, in dB.
     *
     * What the app last wrote to it — the knob's own level plus the phone's
     * slider offset — or what the DAC reads back when nothing has been
     * written yet. Null when the DAC has no volume control of its own, or
     * when its level cannot be read: then there is no absolute level to
     * name, and the app is scaling the samples instead.
     */
    fun hardwareOutputLevelDb(): Double? {
        if (!active || !hardwareVolume || forceSoftwareVolume) return null
        val written = lastWrittenDb
        if (written != null) return written
        return dacCurrentDb
    }

    fun currentRateHz(): Int = if (active) configuredRateHz else 0

    fun activeWireBits(): Int = if (active) activeWireBits else 0

    fun lastHardwareRateHz(): Int = lastHardwareRate

    fun supportedHardwareRatesHz(): List<Int> = usbAudio.querySupportedSampleRates()

    fun framesWritten(): Long = stream?.framesWritten ?: 0L

    fun isPaused(): Boolean = paused

    /**
     * Pause/resume ISO writes without tearing down the USB session or
     * discarding already-decoded PCM in [pcmQueue]. [flush] clears the queue
     * on explicit seek/stop.
     */
    fun setPaused(value: Boolean) {
        if (!value) revalidateClockOnResume()
        paused = value
        if (value) {
            lastWriteElapsedMs = 0L
        }
        synchronized(pcmLock) {
            pcmLock.notifyAll()
        }
    }

    /**
     * A pause is the longest window in which another app, or the DAC itself,
     * can move the clock. Resuming without re-reading it is how a stale
     * 48 kHz session keeps counting frames at 44.1 kHz.
     */
    private fun revalidateClockOnResume() {
        if (!active || !clockReadbackSupported) return
        val expected = configuredRateHz
        val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
        if (reported > 0 && reported != expected) {
            val reason = "DAC clock is ${reported}Hz after pause, track needs ${expected}Hz"
            Log.w(TAG, "Exclusive USB resume rejected: $reason")
            synchronized(lock) {
                if (active) failLocked(reason)
            }
            return
        }
        lastClockCheckMs = SystemClock.elapsedRealtime()
    }

    /** True while the isochronous stream is still accepting PCM. */
    fun isStreamAlive(): Boolean = active && stream?.isAlive == true

    /**
     * True while PCM is actively queued/writing to the DAC or a USB write
     * completed within the last 750 ms. Prevents the UI seekbar from advancing
     * on wall-clock time when the USB pipeline is starved.
     */
    fun isStreamingAudio(): Boolean {
        if (!active || paused || stream?.isAlive != true) return false
        if (writingInProgress || queuedBytes > 0) return true
        val last = lastWriteElapsedMs
        return last > 0L && (SystemClock.elapsedRealtime() - last) in 0L..750L
    }

    /** Re-anchor the Media3 clock after an explicit seek. */
    fun noteSeek(positionUs: Long) {
        val timeUs = positionUs.coerceAtLeast(0L)
        val rate = configuredRateHz.coerceAtLeast(1)
        val targetFrames = timeUs * rate / C.MICROS_PER_SECOND
        Log.i(
            TAG,
            "seek timeUs=$timeUs targetFrames=$targetFrames rate=$rate " +
                "clock=${stream?.framesWritten} alive=${stream?.isAlive}",
        )
        synchronized(pcmLock) {
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true
            lastWriteElapsedMs = 0L
        }
    }

    /**
     * Follows the system's own media volume, when the system is in charge.
     *
     * A hardware volume key moves the system's media stream, and that is the
     * listener's level — until a USB DAC attaches, at which point Android
     * pins the stream at maximum and the reading says nothing about what the
     * listener chose. A pinned reading is ignored, and the app's own level
     * stands.
     */
    fun syncSystemVolume() {
        val system = MediaVolumeProbe.levelOf(audioManager)
        val usbAttached = MediaVolumeProbe.usbOutputAttached(audioManager)
        if (ListenerVolumePolicy.systemReadIsPinned(system, usbAttached, AppSettings.playerVolume.value)) {
            return
        }
        AppSettings.setPlayerVolume(system)
    }

    /**
     * Opens the exclusive stream at the track's own rate and channel count.
     *
     * There is no resampler on this path, so a rate the DAC's clock cannot
     * lock to must not be streamed anyway: that plays at the wrong pitch and
     * speed, with the underruns showing up as artifacts in quiet passages.
     * The open fails closed instead, and the sink falls back to AudioTrack,
     * which does resample properly.
     */
    fun configure(
        format: Format,
        bitDepthHint: Int? = null,
    ): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..PrecisionAudioSink.MAX_CHANNELS
        ) {
            return false
        }
        val floatSource = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val sourceBits = sourceBitDepth(format.pcmEncoding)
        if (!floatSource && sourceBits == 0) return false
        val resolvedBits = when {
            format.sampleRate > 192_000 -> 32
            format.sampleRate > 48_000 -> 24
            bitDepthHint != null && bitDepthHint > 0 -> bitDepthHint
            sourceBits > 0 -> sourceBits
            else -> 16
        }
        synchronized(lock) {
            if (!wanted) return false
            return runCatching {
                configureLocked(
                    format.sampleRate,
                    format.channelCount,
                    resolvedBits,
                    floatSource,
                    format.pcmEncoding,
                )
            }.onFailure { error ->
                Log.w(TAG, "Exclusive USB configure failed", error)
                teardownLocked(closeDevice = true)
            }.getOrDefault(false)
        }
    }

    fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        isFloatBuffer: Boolean = useFloatWrite,
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        if (paused) return false
        val running = stream
        if (!wanted || !active || running == null || !running.isAlive) return false
        val size = buffer.remaining()
        // Full queue means "try again", like AudioTrack. Blocking here holds
        // ExoPlayer's playback thread, and that thread is what loads the next
        // network bytes. A forward seek then plays the buffered couple of
        // seconds and sticks on the loading spinner.
        synchronized(pcmLock) {
            if (queuedBytes > 0 && queuedBytes + size > MAX_QUEUED_BYTES) return false
        }
        if (startMediaTimeNeedsInit) {
            mediaTimeBaseFrames = running.framesWritten
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            startMediaTimeNeedsInit = false
        }
        val gain = softwareGain()
        val chunk = if (isFloatBuffer) {
            val floats = FloatArray(size / Float.SIZE_BYTES)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            buffer.position(buffer.limit())
            ExclusivePcmGain.apply(floats, gain)
            QueuedPcm(floats, null, 0, size)
        } else {
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = ByteArray(size)
            buffer.get(bytes)
            if (gain < 1f - 1e-6f && gain >= 0f) {
                scalePcmBytes(bytes, encoding, gain)
            }
            QueuedPcm(null, bytes, encoding, size)
        }
        synchronized(pcmLock) {
            // A pause that lands mid-copy must not drop a buffer the caller
            // already handed over; the writer waits while paused anyway.
            if (writerStop) return false
            pcmQueue.addLast(chunk)
            queuedBytes += size
            pcmLock.notifyAll()
        }
        return true
    }

    fun getCurrentPositionUs(): Long {
        val running = stream ?: return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        if (!active || configuredRateHz <= 0 || startMediaTimeNeedsInit) {
            return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        }
        val frames = (running.framesWritten - mediaTimeBaseFrames).coerceAtLeast(0L)
        return startMediaTimeUs + frames * C.MICROS_PER_SECOND / configuredRateHz
    }

    /**
     * Writes are blocking and consume the Media3 buffer. Reporting "pending"
     * while the ISO stream is merely alive makes ExoPlayer wait to drain an
     * AudioTrack that does not exist — next-track / seek stalls for seconds.
     */
    /**
     * ExoPlayer treats "no pending data" as "the sink is idle" and drops to
     * BUFFERING between DASH chunks. While exclusive USB is playing, the
     * isochronous pipeline is that pending audio.
     */
    fun hasPendingData(): Boolean =
        active && (queuedBytes > 0 || (!paused && stream?.isAlive == true))

    fun flush() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            lastWriteElapsedMs = 0L
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true
            // Seek only flushes the sink. ExoPlayer does not call play()
            // again, so a pause flag left set here means every later buffer
            // is refused and the DAC stays silent while the bar moves on.
            paused = false
            pcmLock.notifyAll()
        }
    }

    /**
     * The stream stopped without the device being closed. [flush] discards
     * leftover URBs and marks it running again. [start] would zero the DAC
     * clock and make the sink position jump back to the beginning.
     */
    fun restartIfStopped(): Boolean {
        val running = stream ?: return false
        // A session parked by the clock watchdog (or by teardown) is not a
        // stream to re-arm; restarting it would write the same bytes at the
        // wrong rate again.
        if (!active) return false
        if (running.isAlive) return false
        if (!running.start()) return false
        synchronized(lock) {
            paused = false
            mediaTimeBaseFrames = running.framesWritten
        }
        Log.i(TAG, "seek rearmed clock=${running.framesWritten} startUs=$startMediaTimeUs")
        return true
    }

    fun handleDiscontinuity() {
        // A DASH segment boundary is not a seek. Resetting the sink clock
        // here made ExoPlayer wait forever after the first ~5s chunk
        // ("keeps loading") while the DAC had already stopped.
    }

    /**
     * ExoPlayer sink reset between items. Keep the USB session so the next
     * configure can reuse the stream instead of closeDevice + Feature Unit
     * re-probe (seconds of control-transfer timeouts).
     */
    fun prepareForNextItem() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            paused = false
            pcmLock.notifyAll()
        }
        synchronized(lock) {
            // The listener's level carries over to the next track. Resetting
            // it to full here is what used to make every track boundary jump
            // to maximum volume.
            listenerLevel = AppSettings.playerVolume.value.coerceIn(0f, 1f)
            volumeDirty = true
        }
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = stream?.framesWritten ?: 0L
    }

    fun reset() {
        synchronized(lock) {
            teardownLocked(closeDevice = true)
        }
    }

    fun release() {
        synchronized(lock) {
            wanted = false
            teardownLocked(closeDevice = true)
        }
    }

    /**
     * The listener's own level, 0..1.
     *
     * Applied as an attenuation *from* the level the output already has: the
     * DAC's knob stays where the listener put it, and the phone's slider
     * moves the sound down from there. It is never written as a level of its
     * own — that is what used to stomp the DAC's knob, and since Android
     * pins the media stream at maximum when a DAC attaches, the level
     * written was always "maximum".
     *
     * Only the level itself is set here. Writing it to the DAC is a USB
     * control transfer, and those are slow enough to be heard as a hiccup —
     * so they happen on the writer thread, never on the audio thread that
     * calls this.
     */
    fun setListenerLevel(level: Float) {
        val normalized = level.coerceIn(0f, 1f)
        synchronized(lock) {
            if (listenerLevel == normalized && !volumeDirty) return
            listenerLevel = normalized
            volumeDirty = true
        }
    }

    private fun configureLocked(
        sampleRate: Int,
        channelCount: Int,
        sourceBits: Int,
        floatSource: Boolean,
        pcmEncoding: Int,
    ): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return failLocked("no USB audio device", retryable = true)
        if (!manager.hasPermission(usbDevice)) {
            return failLocked("USB permission missing", retryable = true)
        }
        blockReasonFor(usbDevice.vendorId, usbDevice.productId)?.let { reason ->
            return failLocked("disabled for this DAC after a failed test ($reason) — turn USB Exclusive off and on to retry")
        }
        val deviceKey = "${usbDevice.vendorId}:${usbDevice.productId}"

        val bits = if (sourceBits > 0) sourceBits else (if (floatSource) 24 else 16)
        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            currentDeviceKey == deviceKey &&
            configuredRateHz == sampleRate &&
            configuredChannelCount == channelCount &&
            active &&
            sourceEncoding == pcmEncoding &&
            useFloatWrite == floatSource
        ) {
            // The session was opened for this exact format; before trusting
            // it again, ask the DAC whether its clock still sits there.
            val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
            if (reported <= 0 || reported == sampleRate) {
                if (reported > 0) {
                    lastHardwareRate = reported
                    clockMatched = true
                    clockReadbackSupported = true
                }
                paused = false
                startMediaTimeNeedsInit = true
                mediaTimeBaseFrames = reuse.framesWritten
                lastClockCheckMs = SystemClock.elapsedRealtime()
                ensureVolumeObserverLocked()
                // The listener's level carries over to the next track; it is
                // not reset to full here.
                listenerLevel = AppSettings.playerVolume.value.coerceIn(0f, 1f)
                volumeDirty = true
                return true
            }
            Log.w(TAG, "reuse rejected: DAC clock is ${reported}Hz, track needs ${sampleRate}Hz")
        }

        stopStreamLocked()
        usbAudio.closeDevice()
        connection = null
        currentDeviceKey = deviceKey
        val info = usbAudio.openDevice(usbDevice)
            ?: return failLocked("openDevice failed" + (usbAudio.lastOpenError?.let { ": $it" } ?: ""))
        // Fills clockSourceRateMap, which lets setSampleRate() prefer the
        // crystal whose GET_RANGE actually covers the track rate.
        usbAudio.querySupportedSampleRates()

        var (alt, wireBits) = usbAudio.findAltSetting(bits, sampleRate, channelCount)
            ?: return failLocked(
                "no USB alt carries ${channelCount}ch ${bits}-bit at ${sampleRate}Hz",
            )
        var rateSetBefore = usbAudio.setSampleRate(sampleRate)
        if (!usbAudio.setAltSetting(alt)) {
            val fallbackAlt = usbAudio.findAltSetting(24, sampleRate, channelCount)
            if (fallbackAlt != null && fallbackAlt.first != alt && usbAudio.setAltSetting(fallbackAlt.first)) {
                alt = fallbackAlt.first
                wireBits = fallbackAlt.second
            } else {
                return failLocked("setAltSetting $alt failed")
            }
        }
        var reported = usbAudio.readSampleRate()
        if (!rateSetBefore || (reported > 0 && reported != sampleRate)) {
            rateSetBefore = usbAudio.setSampleRate(sampleRate)
            reported = usbAudio.readSampleRate()
        }
        // A DAC's PLL needs a moment to relock, and GET_CUR can report the
        // old rate until it does. Give it a bounded settle before calling a
        // disagreeing readback a failure.
        var settleTries = 0
        while (reported > 0 && reported != sampleRate && settleTries < CLOCK_SETTLE_TRIES) {
            Thread.sleep(CLOCK_SETTLE_STEP_MS)
            reported = usbAudio.readSampleRate()
            settleTries++
        }

        // There is no resampler behind this path: the DAC either runs at the
        // track rate or the bytes are played at the wrong speed. A readback
        // that disagrees (or a write to a clock the descriptors do not name,
        // with no readback to check it) is a fail, not a reason to negotiate
        // some other rate.
        val clockReadback = reported > 0
        val clockVerified = if (clockReadback) {
            reported == sampleRate
        } else {
            rateSetBefore && usbAudio.isRateWriteTrusted()
        }
        if (!clockVerified) {
            val detail = if (clockReadback) {
                "DAC clock is ${reported}Hz, track needs ${sampleRate}Hz (no resampling in bit-perfect)"
            } else {
                "DAC did not accept the ${sampleRate}Hz clock request and does not report its rate"
            }
            return failLocked(detail)
        }

        val selected = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    // Playback interface only: headset dongles also expose a
                    // microphone AS interface with the same alt numbers.
                    it.id == info.interfaceId &&
                    it.alternateSetting == alt &&
                    it.endpointCount > 0
            }
        val selectedOut = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }
        if (selectedOut == null) return failLocked("alt $alt has no isochronous OUT endpoint")
        val selectedFeedback = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        val maxPacket = isoPacketBytes(selectedOut.maxPacketSize)
        val dataInterval = selectedOut.interval.coerceAtLeast(1)
        val feedbackPacketSize = selectedFeedback?.maxPacketSize?.let { isoPacketBytes(it) }
            ?: info.feedbackPacketSize
        val feedbackInterval = selectedFeedback?.interval?.coerceAtLeast(1) ?: dataInterval
        val created = UsbAudioStream(
            info.fd,
            info.interfaceId,
            selectedOut.address,
            selectedFeedback?.address ?: 0,
            sampleRate,
            channelCount,
            wireBits,
            maxPacket,
            dataInterval,
            feedbackPacketSize,
            feedbackInterval,
        )
        if (!created.isReady || !created.start()) {
            created.release()
            return failLocked("decent USB start failed")
        }
        connection = info.connection
        stream = created
        sourceEncoding = pcmEncoding
        activeWireBits = wireBits
        useFloatWrite = floatSource
        configuredRateHz = sampleRate
        configuredChannelCount = channelCount
        active = true
        lastFailureReason = null
        lastFailureRetryable = false
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        clockReadbackSupported = clockReadback
        clockMatched = clockReadback
        lastHardwareRate = if (clockReadback) reported else 0
        lastClockCheckMs = SystemClock.elapsedRealtime()
        // The DAC's own level, before the app adds anything to it. Read once
        // here and re-read whenever the listener moves the DAC's knob.
        dacBaseDb = 0.0
        dacBaseKnown = false
        lastWrittenDb = null
        dacCurrentDb = null
        volumeDirty = false
        if (!forceSoftwareVolume) {
            val controlId = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1
                }?.id ?: 0
            val volumeControl = UacFeatureVolume(info.connection, controlId)
            featureVolume = volumeControl
            val attached = volumeControl.attach()
            featureVolumeAttached = attached
            hardwareVolume = attached
            if (attached) {
                // The DAC's own level, before the app touched it: the
                // listener's knob, and the base the phone's slider offsets
                // from. What the control reads now is the same thing unless
                // the verification failed to put it back.
                val base = volumeControl.originalDb() ?: volumeControl.currentDb()
                if (base != null) {
                    dacBaseDb = base
                    dacBaseKnown = true
                }
            }
        } else {
            featureVolume = null
            featureVolumeAttached = false
            hardwareVolume = false
        }
        Log.i(
            TAG,
            "decent USB started ${usbDevice.productName} ${sampleRate}Hz " +
                "alt=$alt wireBits=$wireBits reported=$reported clockMatched=$clockMatched " +
                "hardwareVolume=$hardwareVolume",
        )

        ensureVolumeObserverLocked()
        listenerLevel = AppSettings.playerVolume.value.coerceIn(0f, 1f)
        // Applied here rather than left to the writer thread: this is the
        // session's first level, and the writer has not started yet.
        applyVolumeLocked()
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume level=$listenerLevel " +
                "dacBase=${dacBaseDb}dB softwareGain=$softwareGainValue",
        )
        sessionSummary = "started rate=${sampleRate}Hz ch=$channelCount alt=$alt wireBits=$wireBits " +
            "reported=$reported clockMatched=$clockMatched readback=$clockReadbackSupported " +
            "hardwareVolume=$hardwareVolume maxPacket=$maxPacket interval=$dataInterval " +
            "feedback=${selectedFeedback != null}"
        startWriterLocked(created)
        return true
    }

    // ── Fork: safety guard + diagnostics ──

    private fun blockReasonFor(vendorId: Int, productId: Int): String? =
        sessionBlockedDevices[vendorId.toString() + ":" + productId]

    /** Forget every DAC the guard disabled. */
    fun clearBlockedDevices() {
        sessionBlockedDevices.clear()
        val prefs = volumePrefs ?: return
        val editor = prefs.edit()
        prefs.all?.keys?.filter { it.startsWith(KEY_BLOCK_PREFIX) }?.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Text for the signal-path "copy USB diagnostic" button. */
    fun diagnosticReport(): String = buildString {
        append("BitChord USB diagnostic\n")
        append("active=$active wanted=$wanted rate=$configuredRateHz clockMatched=$clockMatched ")
        append("hardwareVolume=$hardwareVolume forceSoftwareVolume=$forceSoftwareVolume\n")
        if (sessionSummary.isNotEmpty()) append(sessionSummary).append('\n')
        lastFailureReason?.let { append("lastFailure=").append(it).append('\n') }
        append(runCatching { usbAudio.diagnosticReport() }.getOrDefault(""))
    }

    private fun ensureVolumeObserverLocked() {
        if (volumeReceiverRegistered) return
        volumeReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                volumeReceiver,
                IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterVolumeObserverLocked() {
        if (!volumeReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
    }

    /**
     * Applies the listener's level.
     *
     * Two ways to do it, and the DAC's own control decides which:
     *
     *  - **The DAC's own volume control.** The level written is the DAC's
     *    own level plus the slider's attenuation: an *offset*, never a level
     *    of its own. The listener's knob stays where they put it, and the
     *    phone's slider moves the sound down from there. Writing an absolute
     *    level instead is what used to stomp the knob — and since Android
     *    pins the media stream at maximum when a DAC attaches, the absolute
     *    level was always "maximum", which is how a DAC ended up at full
     *    volume with the phone's slider showing something else entirely.
     *  - **Software.** When the DAC has no usable volume control, or its
     *    level cannot be read, the samples are scaled instead — with the
     *    same curve the phone's own mixer uses, so that the slider means the
     *    same thing on every path.
     */
    private fun applyVolumeLocked() {
        val level = listenerLevel.coerceIn(0f, 1f)
        if (hardwareVolume && !forceSoftwareVolume) {
            val control = featureVolume
            val base = if (control != null) dacBaseDbLocked(control) else null
            if (base != null && control != null) {
                val target = ListenerVolumePolicy.dacTargetDb(
                    baseDb = base,
                    level = level,
                    minDb = control.minDb(),
                    maxDb = control.maxDb(),
                )
                val current = dacCurrentDb
                val alreadyThere = current != null && kotlin.math.abs(target - current) <= 0.05
                val written = lastWrittenDb
                val needsWrite = !alreadyThere &&
                    (written == null || kotlin.math.abs(target - written) > 0.05)
                if (needsWrite) {
                    if (control.setDb(target)) {
                        lastWrittenDb = target
                        softwareGainValue = 1f
                        return
                    }
                    Log.w(TAG, "Feature Unit SET_CUR failed; falling back to perceptual software gain")
                    hardwareVolume = false
                } else {
                    // The DAC is already where it should be — at full volume
                    // with the knob where the listener left it, nothing is
                    // written to it at all.
                    lastWrittenDb = target
                    softwareGainValue = 1f
                    return
                }
            } else {
                // The DAC's own level cannot be read, so there is no base to
                // offset from. The app scales the samples instead.
                hardwareVolume = false
            }
        }
        softwareGainValue = ListenerVolumePolicy.perceptualGain(level)
    }

    /**
     * The DAC's own level, which the phone's slider offsets from.
     *
     * Read rather than remembered, so a knob the listener turns while the
     * app is playing is picked up: when the DAC's level is no longer what
     * this app last wrote to it, the listener has moved it, and that is the
     * new base.
     */
    private fun dacBaseDbLocked(control: UacFeatureVolume): Double? {
        val current = control.currentDb()
        dacCurrentDb = current
        if (current == null) return null
        val written = lastWrittenDb
        val epsilon = control.resolutionDb() + 0.5
        if (written != null && kotlin.math.abs(current - written) > epsilon) {
            dacBaseDb = current
            lastWrittenDb = null
            Log.i(TAG, "DAC level moved to ${current}dB; the phone's slider offsets from there")
        }
        if (!dacBaseKnown) {
            dacBaseDb = current
            dacBaseKnown = true
        }
        return dacBaseDb
    }

    private fun startWriterLocked(target: UsbAudioStream) {
        stopWriterLocked()
        writerStop = false
        flushRequested = false
        val thread = Thread({ writeLoop(target) }, "ExclusiveUsbWriter")
        writer = thread
        thread.start()
    }

    /**
     * Signal the writer thread to exit, drop queued PCM, and wait for it to
     * finish. The join is unbounded on purpose: a timeout here is what let the
     * old code release() the native context while the writer was still inside
     * write() — a use-after-free. Callers MUST stop the native stream first
     * (see [stopStreamLocked]) so a write() blocked on a full URB ring returns
     * and this join is guaranteed to terminate.
     */
    private fun stopWriterLocked() {
        writerStop = true
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            pcmLock.notifyAll()
        }
        writer?.join()
        writer = null
    }

    private fun writeLoop(target: UsbAudioStream) {
        while (!writerStop) {
            val next = synchronized(pcmLock) {
                if (!writerStop && !flushRequested && (paused || pcmQueue.isEmpty())) {
                    pcmLock.wait(20)
                }
                if (writerStop) return@synchronized null
                if (flushRequested) {
                    flushRequested = false
                    return@synchronized FLUSH_MARKER
                }
                if (paused || pcmQueue.isEmpty()) return@synchronized IDLE_MARKER
                val chunk = pcmQueue.removeFirst()
                queuedBytes = (queuedBytes - chunk.byteSize).coerceAtLeast(0)
                chunk
            } ?: break
            if (next === FLUSH_MARKER) {
                runCatching { target.flush() }
                mediaTimeBaseFrames = target.framesWritten
                continue
            }
            if (next === IDLE_MARKER) {
                runCatching { target.pump() }
                continue
            }
            if (!clockStillMatches()) break
            maybeApplyVolume()
            maybeReapplyDacLevel()
            if (!target.isAlive) break
            writingInProgress = true
            lastWriteElapsedMs = SystemClock.elapsedRealtime()
            try {
                if (next.floats != null) target.write(next.floats)
                else if (next.raw != null) target.writeRaw(next.raw, next.encoding)
            } finally {
                writingInProgress = false
                lastWriteElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * Applies a level change that has been waiting.
     *
     * Called from the writer thread: the transfer to the DAC is slow enough
     * to be heard as a hiccup on the audio thread.
     */
    private fun maybeApplyVolume() {
        if (!volumeDirty) return
        synchronized(lock) {
            if (!volumeDirty) return
            volumeDirty = false
            applyVolumeLocked()
        }
    }

    /**
     * Keeps the phone's offset on top of the DAC's own level.
     *
     * The listener can turn the DAC's knob while the app is playing. The
     * knob moves the DAC's level, the app's offset would be lost with it,
     * and the sound would jump up by whatever the offset was. Re-reading
     * the level every couple of seconds puts the offset back on top of
     * wherever the knob now is.
     */
    private fun maybeReapplyDacLevel() {
        if (!active || !hardwareVolume || forceSoftwareVolume) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDacLevelCheckMs < DAC_LEVEL_CHECK_INTERVAL_MS) return
        lastDacLevelCheckMs = now
        synchronized(lock) { applyVolumeLocked() }
    }

    /**
     * Writer-thread clock watchdog. A DAC that moves its clock mid-stream
     * plays the same bytes at another pitch; the sink has to let go rather
     * than keep writing. Teardown cannot run here because it joins this
     * thread, so the session is parked and the next configure() tears it
     * down from the caller's thread.
     */
    private fun clockStillMatches(): Boolean {
        if (!clockReadbackSupported) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastClockCheckMs < CLOCK_CHECK_INTERVAL_MS) return true
        lastClockCheckMs = now
        val expected = configuredRateHz
        val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
        if (reported <= 0 || reported == expected) return true
        val reason = "DAC clock moved to ${reported}Hz mid-track (expected ${expected}Hz)"
        Log.w(TAG, "Exclusive USB writer stopped: $reason")
        currentDeviceKey?.let { sessionBlockedDevices[it] = reason }
        lastFailureReason = "stopped for safety: $reason"
        lastFailureRetryable = false
        clockMatched = false
        active = false
        writerStop = true
        return false
    }

    private fun stopStreamLocked() {
        val running = stream
        // Stop the native stream FIRST. This flips running=false so a write()
        // blocked on a full URB ring returns (the native reap loop wakes roughly
        // every 200ms and breaks). Only then can stopWriterLocked()'s unbounded
        // join() terminate — joining before stop() would always time out and
        // leave the writer alive.
        runCatching { running?.stop() }
        stopWriterLocked()
        // The writer thread is now guaranteed dead, so releasing native state is
        // safe. Releasing it while the writer was still alive was a use-after-free.
        if (running != null) {
            runCatching { running.drainUrbs() }
            runCatching { running.release() }
        }
        stream = null
        active = false
    }

    private fun teardownLocked(closeDevice: Boolean) {
        stopStreamLocked()
        if (closeDevice) {
            runCatching { usbAudio.closeDevice() }
            connection = null
        }
        featureVolume = null
        featureVolumeAttached = false
        volumeProbed = false
        hardwareVolume = false
        clockMatched = false
        clockReadbackSupported = false
        configuredRateHz = 0
        configuredChannelCount = 0
        active = false
        softwareGainValue = 1f
        dacBaseDb = 0.0
        dacBaseKnown = false
        lastWrittenDb = null
        dacCurrentDb = null
        volumeDirty = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        paused = false
        if (closeDevice) unregisterVolumeObserverLocked()
    }

    private class QueuedPcm(
        val floats: FloatArray?,
        val raw: ByteArray?,
        val encoding: Int,
        val byteSize: Int,
    )

    private fun failLocked(reason: String, retryable: Boolean = false): Boolean {
        Log.w(TAG, "Exclusive USB fail-open: $reason")
        lastFailureReason = reason
        lastFailureRetryable = retryable
        teardownLocked(closeDevice = true)
        return false
    }

    private companion object {
        const val TAG = "ExclusiveUsb"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val KEY_BLOCK_PREFIX = "blocked_dac_"
        const val CLOCK_CHECK_INTERVAL_MS = 5_000L
        /** How often the DAC's own level is re-read while the session plays. */
        const val DAC_LEVEL_CHECK_INTERVAL_MS = 2_000L
        const val CLOCK_SETTLE_TRIES = 10
        const val CLOCK_SETTLE_STEP_MS = 20L
        const val RAW_PCM16 = 2
        const val RAW_PCM24 = 0x15
        const val RAW_PCM32 = 0x16
        const val MAX_QUEUED_BYTES = 512 * 1024
        val FLUSH_MARKER = QueuedPcm(null, null, -1, 0)
        val IDLE_MARKER = QueuedPcm(null, null, -2, 0)

        fun isoPacketBytes(raw: Int): Int {
            if (raw <= 0) return 0
            val extra = (raw shr 11) and 0x3
            val size = raw and 0x7FF
            return if (extra > 0) size * (1 + extra) else raw
        }

        fun minIsoPacketBytes(rateHz: Int, channels: Int, bitDepth: Int, interval: Int = 1): Int {
            val bpf = ((bitDepth + 7) / 8).coerceAtLeast(1) * channels.coerceIn(1, 8)
            val microframes = if (interval > 1) 1 shl (interval - 1).coerceAtMost(4) else 1
            val frames = ((rateHz + 7999) / 8000) * microframes + 1
            return frames * bpf
        }

        fun sourceBitDepth(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT, RAW_PCM16 -> 16
            C.ENCODING_PCM_24BIT, RAW_PCM24 -> 24
            C.ENCODING_PCM_32BIT, RAW_PCM32 -> 32
            else -> 0
        }

        fun writeRawEncoding(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT, RAW_PCM16 -> RAW_PCM16
            C.ENCODING_PCM_24BIT, RAW_PCM24 -> RAW_PCM24
            C.ENCODING_PCM_32BIT, RAW_PCM32 -> RAW_PCM32
            else -> -1
        }

        private fun scalePcmBytes(bytes: ByteArray, rawEncoding: Int, gain: Float) {
            if (gain <= 0f) {
                bytes.fill(0)
                return
            }
            when (rawEncoding) {
                RAW_PCM16 -> {
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8))
                            .toShort().toInt()
                        val scaled = (sample * gain + if (sample >= 0) 0.5f else -0.5f).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        i += 2
                    }
                }
                RAW_PCM24 -> {
                    var i = 0
                    while (i + 2 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt()
                        var sample = (b2 shl 16) or (b1 shl 8) or b0
                        if (sample and 0x800000 != 0) sample -= 0x1000000
                        val scaled = (sample * gain.toDouble() + if (sample >= 0) 0.5 else -0.5).toInt()
                            .coerceIn(-0x800000, 0x7FFFFF)
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        i += 3
                    }
                }
                RAW_PCM32 -> {
                    var i = 0
                    while (i + 3 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        val b3 = bytes[i + 3].toInt()
                        val sample = (b3.toLong() shl 24) or (b2.toLong() shl 16) or (b1.toLong() shl 8) or (b0.toLong() and 0xFF)
                        val scaled = (sample * gain.toDouble()).toLong()
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        bytes[i + 3] = ((scaled shr 24) and 0xFF).toByte()
                        i += 4
                    }
                }
            }
        }
    }
}
