package com.music.bitchord.playback.audio

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * The outgoing sink's splice into the loop mix, with a stand-in for the
 * native renderer: when the takeover is decided, what is heard if a disarm
 * wins, and what the sink does once the mix it switched to fails.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PrecisionAudioSinkLoopSpliceTest {

    private class Delegate : AudioSink {
        val written = mutableListOf<ShortArray>()
        override fun setListener(listener: AudioSink.Listener) {}
        override fun supportsFormat(format: Format) = true
        override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getCurrentPositionUs(sourceEnded: Boolean) = 0L
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {}
        override fun play() {}
        override fun handleDiscontinuity() {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            written += ShortArray(b.remaining() / 2) { b.getShort() }
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() {}
        override fun isEnded() = false
        override fun hasPendingData() = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled() = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
        override fun getAudioAttributes(): AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun getAudioTrackBufferSizeUs() = 0L
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun pause() {}
        override fun flush() {}
        override fun reset() {}
        override fun release() {}
    }

    /** An exclusive route that counts the times a sink tries to open it. */
    private class CountingSession : ExclusiveSession {
        var opens = 0
        /** Whether opening succeeds, as a granted DAC's would. */
        var opensSucceed = false
        var writes = 0
        private var open = false
        override val pathLabel = "test"
        override val pathDetail = "test"
        override val transport = TransportType.DIRECT_USB
        override val lastFailureReason: String? = null
        override fun canOpen() = true
        override fun configure(format: Format, bitDepthHint: Int?): Boolean { opens++; open = opensSucceed; return open }
        override fun currentRateHz() = 48_000
        override fun activeWireBits() = 16
        override fun failureIsRetryable() = false
        override fun isPaused() = false
        override fun setPaused(value: Boolean) = Unit
        override fun setListenerLevel(level: Float) = Unit
        override fun usesHardwareVolume() = false
        override fun softwareGain() = 1f
        override fun hardwareOutputLevelDb(): Double? = null
        override fun write(buffer: ByteBuffer, presentationTimeUs: Long, isFloatBuffer: Boolean): Boolean {
            writes++
            buffer.position(buffer.limit())
            return true
        }
        override fun isStreamAlive() = false
        override fun restartIfStopped() = false
        override fun getCurrentPositionUs() = 0L
        override fun hasPendingData() = false
        override fun flush() = Unit
        override fun handleDiscontinuity() = Unit
        override fun prepareForNextItem() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }

    private val rate = 48_000
    private var bitPerfect = false
    private val delegate = Delegate()
    private val session = CountingSession()
    private val chain = DspChain()
    private val sink = PrecisionAudioSink(
        delegate = delegate,
        dspChain = chain,
        enableFloatOutput = false,
        bitPerfect = { bitPerfect },
        exclusiveSession = { session },
        exclusiveWanted = { bitPerfect },
    )
    private val port: LoopMixPort get() = chain.transition.loop

    private fun configure(channels: Int = 2) {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(channels)
            .setSampleRate(rate)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
    }

    /** The loop start at track 1 s; the deck renders a steady 0.25. */
    private fun arm(render: LoopMixPort.Render = LoopMixPort.Render { _, out, offset, frames ->
        out.fill(0.25f, offset, offset + 2 * frames); true
    }) {
        port.armOutgoing(
            LoopMixPort.OutArm(LoopMixRenderer(rate), takeoverUs = 1_000_000L, sampleRate = rate, firstFrame = -4_800L, render = render),
        )
        // The decks' pre-roll, from 100 ms before the loop start up to the
        // crossing: rendered, never heard.
        feed(900_000L, 3_600) // frames -4800 to -1200, where the crossing begins
        check(delegate.written.last().all { it.toInt() == 1000 }) { "pre-roll was heard" }
        check(!port.tookOver && !port.interrupted)
    }

    /** [frames] of the song, a constant 1000, from track [startUs]. */
    private fun song(startUs: Long, frames: Int): Pair<ByteBuffer, Long> {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { b.putShort(1000) }
        b.flip()
        return b to startUs
    }

    private fun feed(startUs: Long, frames: Int): Boolean {
        val (b, us) = song(startUs, frames)
        return sink.handleBuffer(b, us, 1)
    }

    // 25 ms before the loop start (1 s) is where the crossing is first heard.
    private val crossingUs = 1_000_000L - 20_000L

    @Test
    fun theTakeoverIsDecidedAtTheFirstAudibleBlockNotAtTheLoopStart() {
        configure()
        arm()
        feed(crossingUs, 240) // 5 ms, all inside the crossing, still before the loop start
        assertTrue("the crossing was heard, so the takeover must be committed", port.tookOver)
        assertTrue("the block carries the mix", delegate.written.last().any { it.toInt() != 1000 })
    }

    @Test
    fun aDisarmThatWinsLeavesTheSongUntouched() {
        configure()
        // The controller disarms while the sink is rendering the first audible
        // block: enabled only once the pre-roll is through, so the disarm races
        // the takeover itself, not an arm already gone.
        var disarmOnRender = false
        var disarmedDuringRender = false
        arm(LoopMixPort.Render { _, out, offset, frames ->
            if (disarmOnRender && !disarmedDuringRender) {
                port.disarm()
                disarmedDuringRender = true
            }
            out.fill(0.25f, offset, offset + 2 * frames); true
        })
        disarmOnRender = true
        feed(crossingUs, 240)
        assertTrue("the audible render never ran", disarmedDuringRender)
        assertFalse(port.tookOver)
        assertArrayEquals("mixed audio reached the delegate after the disarm won", ShortArray(480) { 1000 }, delegate.written.last())
    }

    @Test
    fun aMixThatFailsAfterTheTakeoverStaysSilentUntilItIsRetired() {
        configure()
        var fail = false
        arm(LoopMixPort.Render { _, out, offset, frames ->
            if (fail) return@Render false
            out.fill(0.25f, offset, offset + 2 * frames); true
        })
        // Through the loop start: the renderer's blocks follow on.
        assertFalse(feed(crossingUs, 1_920))
        fail = true
        feed(crossingUs, 1_920) // the renderer is gone: a silent block, and the arm is called off
        assertTrue(port.interrupted)
        val before = delegate.written.size
        // The same decoder buffer comes back: it must not be played again.
        assertFalse(feed(crossingUs, 1_920))
        assertEquals("the held song input was replayed", before, delegate.written.size)
    }

    @Test
    fun anArmOnASinkNoLongerStereoIsCalledOff() {
        configure(channels = 6)
        port.armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(rate), takeoverUs = 1_000_000L, sampleRate = rate, firstFrame = -4_800L))
        val b = ByteBuffer.allocateDirect(240 * 12).order(ByteOrder.nativeOrder())
        repeat(240 * 6) { b.putShort(1000) }
        b.flip()
        sink.handleBuffer(b, crossingUs, 1)
        assertTrue(port.interrupted)
    }

    @Test
    fun bitPerfectWaitsForAnAudibleCrossingBeforeTakingTheExclusiveRoute() {
        configure()
        arm()
        feed(crossingUs, 240)
        assertTrue(port.tookOver)
        bitPerfect = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        feed(crossingUs + 5_000L, 240)
        assertEquals("the route was opened over the crossing", 0, session.opens)
        // Once the controller retires the mix and its recovery seek flushes the sink, the route is taken.
        port.disarm()
        sink.flush()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        feed(crossingUs + 10_000L, 240)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        feed(crossingUs + 15_000L, 240)
        assertEquals(1, session.opens)
    }

    @Test
    fun anInterruptionRecordedBeforeTheSinkSawTheArmStands() {
        configure()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 2_000_000L))
        // Called off before this sink processed a buffer for it.
        assertTrue(port.interrupt(port.armed.generation))
        feed(900_000L, 240)
        assertTrue("adopting the arm cleared its interruption", port.interrupted)
        assertArrayEquals("a rejected arm still muted the song", ShortArray(480) { 1000 }, delegate.written.last())
    }

    @Test
    fun aFailedRenderQueuesNoSilenceAheadOfTheRetirement() {
        configure()
        var fail = false
        arm(LoopMixPort.Render { _, out, offset, frames ->
            if (fail) return@Render false
            out.fill(0.25f, offset, offset + 2 * frames); true
        })
        assertFalse(feed(crossingUs, 1_920))
        val before = delegate.written.size
        fail = true
        assertFalse(feed(crossingUs, 1_920))
        assertEquals("blocks were generated after the renderer failed", before, delegate.written.size)
        assertTrue(port.interrupted)
    }

    @Test
    fun theTakeoverBufferIsNotWrittenAfterTheMixIsRetired() {
        configure()
        arm()
        assertFalse(feed(crossingUs, 1_920)) // through the loop start: taken over
        assertTrue(port.tookOver)
        val before = delegate.written.size
        port.disarm()
        // The renderer hands the held, pre-takeover buffer back.
        assertFalse(feed(crossingUs, 1_920))
        assertEquals("the stale buffer was written with its old timestamp", before, delegate.written.size)
        // The controller's seek flushes the sink: the song plays from there.
        sink.flush()
        assertTrue(feed(5_000_000L, 240))
        assertEquals(before + 1, delegate.written.size)
    }

    @Test
    fun theRouteIsNotOpenedOverTheHeldBufferBetweenRetirementAndTheRecoveryFlush() {
        session.opensSucceed = true
        configure()
        arm()
        assertFalse(feed(crossingUs, 1_920)) // through the loop start: taken over
        assertTrue(port.tookOver)
        bitPerfect = true
        // The controller retires the mix on bit-perfect; its recovery seek is still in flight.
        port.disarm()
        val delegateWrites = delegate.written.size
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        assertFalse("the held pre-takeover buffer was consumed", feed(crossingUs, 1_920))
        assertEquals("the exclusive route opened before the recovery flush", 0, session.opens)
        assertEquals("the held buffer reached the DAC", 0, session.writes)
        assertEquals("the held buffer reached the delegate", delegateWrites, delegate.written.size)
        // The seek lands: from the flush on, the route is taken.
        sink.flush()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        feed(5_000_000L, 240)
        assertEquals(1, session.opens)
    }

    @Test
    fun aRenderThatFailsMidCrossingHoldsTheOutputUntilTheFlush() {
        configure()
        var fail = false
        arm(LoopMixPort.Render { _, out, offset, frames ->
            if (fail) return@Render false
            out.fill(0.25f, offset, offset + 2 * frames); true
        })
        feed(crossingUs, 240) // the crossing starts: committed and heard
        assertTrue(port.tookOver)
        val before = delegate.written.size
        fail = true
        // Still before the loop start: the song must not come back mid-crossing.
        assertFalse(feed(crossingUs + 5_000L, 240))
        assertEquals("the song came back mid-crossing", before, delegate.written.size)
        assertFalse(feed(crossingUs + 5_000L, 240))
        assertEquals(before, delegate.written.size)
        sink.flush()
        assertTrue(feed(5_000_000L, 240))
        assertEquals(before + 1, delegate.written.size)
    }

    @Test
    fun bitPerfectStopsAnIncomingArmThatHadStartedAndHoldsUntilTheFlush() {
        configure()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 2_000_000L))
        feed(900_000L, 240) // muted ahead of the handover: started on this sink
        assertTrue(delegate.written.last().all { it.toInt() == 0 })
        val before = delegate.written.size
        bitPerfect = true
        assertFalse("a bit-perfect buffer was muted or written mid-arm", feed(905_000L, 240))
        assertTrue(port.interrupted)
        assertEquals(before, delegate.written.size)
        sink.flush()
        assertTrue(feed(5_000_000L, 240))
        assertTrue("bit-perfect output after the flush is the song's own", delegate.written.last().all { it.toInt() == 1000 })
    }

    @Test
    fun theLoopLimiterHoldsOnlyOnBlocksTheDeckWasMixedInto() {
        configure()
        arm() // pre-roll: rendered, not mixed in
        assertFalse("the limiter held on dry pre-roll", chain.mixing)
        feed(crossingUs, 240)
        assertTrue("the crossing block carries the mix", chain.mixing)
    }
}
