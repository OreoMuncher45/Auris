package com.music.bitchord.playback.smart

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * Where Automix's two ONNX models come from.
 *
 * Today they ride in the APK and are unpacked into `filesDir` the first time a
 * tracker asks for one: ONNX Runtime wants a file it can memory-map, and an
 * asset inside the APK is not one.
 *
 * BitChord fetches them once from a release instead, the first time Automix
 * is switched on, so the APK is about 30 MB lighter for the many installs that
 * never use Automix. That path is here, behind [DOWNLOAD], and off until the
 * download source is decided: with it on, the copies land at the same
 * `filesDir` paths the APK unpacks to, so an install that had the bundled
 * models keeps them and downloads nothing. Turning it on also means setting
 * [RELEASE_URL] and leaving the `.onnx` assets out of the APK.
 *
 * Until a model is on disk the trackers simply find none, and Automix falls
 * back to plain crossfades, exactly as it does for any other analysis failure.
 */
object AutomixModels {

    /**
     * The one switch: false reads the models bundled in the APK, true fetches
     * them from [RELEASE_URL] on first use.
     */
    const val DOWNLOAD = false

    /**
     * The release the weights are fetched from, each attached as
     * `model_<file>`, or blank while the source is undecided. A copy is only
     * kept if it matches the size and SHA-256 below, so new weights need a new
     * app version, not just new assets.
     */
    private const val RELEASE_URL = ""

    class Model(val fileName: String, val bytes: Long, val sha256: String)

    val BEAT = Model(
        BeatTracker.MODEL_ASSET,
        23_138_816,
        "7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd",
    )
    val VOCALS = Model(
        VocalTracker.MODEL_ASSET,
        9_054_206,
        "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
    )
    private val ALL = listOf(BEAT, VOCALS)

    /** Both models together, for a line that says what is being fetched. */
    val totalBytes: Long = ALL.sumOf { it.bytes }

    sealed interface State {
        data object Ready : State
        /** Not on disk, and nothing fetching them: Automix is off, or has not asked yet. */
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(if (DOWNLOAD) State.Missing else State.Ready)
    val state: StateFlow<State> = _state.asStateFlow()

    private const val TAG = "AutomixModels"

    /** After a failed fetch, how long an analysis pass waits before trying again on its own. */
    private const val RETRY_AFTER_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var filesDir: File? = null
    @Volatile private var assets: AssetManager? = null
    private var job: Job? = null
    private var failedAt = 0L

    /**
     * Records where the models live. With [DOWNLOAD] on it also settles whether
     * they are already here and, whenever Automix is (or becomes) on without
     * them, starts fetching them.
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (filesDir != null) return
            filesDir = context.applicationContext.filesDir
            assets = context.applicationContext.assets
        }
        if (!DOWNLOAD) return
        if (ALL.all(::isPresent)) _state.value = State.Ready
        scope.launch {
            AppSettings.smartFadeEnabled.collect { on ->
                // Switching Automix on is the user asking for it now, so a
                // recent failure does not hold this attempt back.
                if (on) request(force = true)
            }
        }
    }

    /**
     * The path of [model] on disk, for a tracker about to open it.
     *
     * Bundled, unpacked from the APK the first time it is asked for. Downloaded,
     * throws [FileNotFoundException] while it is not there yet — the trackers
     * treat that like any unreadable model and produce nothing — and makes sure
     * a fetch is under way so a later pass finds it.
     */
    fun path(model: Model): String {
        val file = fileFor(model) ?: throw FileNotFoundException("AutomixModels.install was never called")
        if (!DOWNLOAD) return unpacked(model, file)
        if (isPresent(model)) return file.absolutePath
        request(force = false)
        throw FileNotFoundException("${model.fileName} has not been downloaded yet")
    }

    /** A Download / Retry action: fetch whatever is missing now, whether or not Automix is on. */
    fun download() {
        if (DOWNLOAD) request(force = true)
    }

    /**
     * A Delete action: removes both models (and any half-finished download) to
     * free the space. Refused while a download is running, and with nothing to
     * delete while the models are bundled. The caller turns Automix off first,
     * or the next analysis pass would fetch them straight back.
     */
    fun delete(): Boolean {
        if (!DOWNLOAD) return false
        synchronized(lock) {
            if (job?.isActive == true) return false
            for (model in ALL) {
                fileFor(model)?.let { file ->
                    file.delete()
                    File(file.parentFile, "${model.fileName}.part").delete()
                }
            }
            failedAt = 0L
            _state.value = State.Missing
        }
        Log.i(TAG, "Automix models deleted")
        return true
    }

    private fun fileFor(model: Model): File? = filesDir?.let { File(it, model.fileName) }

    /** The bundled copy, unpacked once; a copy cut short by a crash is the wrong size and unpacked again. */
    private fun unpacked(model: Model, file: File): String {
        if (file.length() != model.bytes) {
            val source = assets ?: throw FileNotFoundException("AutomixModels.install was never called")
            source.open(model.fileName).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * A file of exactly the published size is taken as the model. Every copy
     * this class downloads is checked against its SHA-256 before it is renamed
     * into place, and the copies unpacked from the APK are the same bytes.
     */
    private fun isPresent(model: Model): Boolean = fileFor(model)?.length() == model.bytes

    private fun request(force: Boolean) {
        synchronized(lock) {
            if (filesDir == null || job?.isActive == true) return
            if (ALL.all(::isPresent)) {
                _state.value = State.Ready
                return
            }
            if (!force && failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS) return
            job = scope.launch { fetchMissing() }
        }
    }

    private fun fetchMissing() {
        val missing = ALL.filterNot(::isPresent)
        val total = missing.sumOf { it.bytes }.toFloat()
        var done = 0L
        _state.value = State.Downloading(0f)
        try {
            for (model in missing) {
                fetch(model) { read ->
                    _state.value = State.Downloading(((done + read) / total).coerceIn(0f, 1f))
                }
                done += model.bytes
            }
            synchronized(lock) { failedAt = 0L }
            _state.value = State.Ready
            Log.i(TAG, "Automix models ready")
        } catch (e: Exception) {
            Log.w(TAG, "Automix model download failed", e)
            synchronized(lock) { failedAt = SystemClock.elapsedRealtime() }
            _state.value = State.Failed
        }
    }

    private fun fetch(model: Model, progress: (Long) -> Unit) {
        val target = fileFor(model) ?: return
        if (RELEASE_URL.isBlank()) throw IOException("No download source for ${model.fileName}")
        val part = File(target.parentFile, "${model.fileName}.part")
        val url = RELEASE_URL + model.fileName
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "Fetching $url")
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${model.fileName}")
            val body = response.body ?: throw IOException("Empty body for ${model.fileName}")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read > model.bytes) throw IOException("${model.fileName} is larger than expected")
                        if (read - lastReport >= 256 * 1024) {
                            lastReport = read
                            progress(read)
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (read != model.bytes || hash != model.sha256) {
                part.delete()
                throw IOException("${model.fileName} failed verification ($read bytes, sha256 $hash)")
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Could not move ${model.fileName} into place")
        }
        progress(model.bytes)
    }
}
