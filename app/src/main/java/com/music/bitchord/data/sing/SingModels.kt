package com.music.bitchord.data.sing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The karaoke models, and the business of getting one onto the phone.
 *
 * None of them ship inside the app. The APK is already 93 MB in its
 * universal form and 43 MB for an arm64 phone, and a separator model adds
 * another 15 to 30 MB to a download that most listeners will never make --
 * so the model is fetched when someone asks for it, kept in the app's own
 * storage, and can be deleted again from the same screen that downloaded it.
 *
 * All of them here are the MDX-Net family and share one shape: a stereo
 * complex spectrogram of 2048 bins over 6144-sample windows. That is what
 * the native front end reads, and it is why the catalogue does not carry
 * the other families the upstream project offers -- Kim's model needs 3072
 * bins over a 7680-sample window, the second karaoke model a 5120-sample
 * one, the VR architecture a different front end altogether, and Apple's
 * own model is not an ONNX graph at all. Each of those is a different
 * window, a different front end, or a different runtime.
 */
object SingModels {

    /**
     * One downloadable model.
     *
     * [title] and [summary] are what the listener reads; they are written
     * in plain words on purpose. A model name is not a description of what
     * it will do to a song.
     */
    data class Model(
        val id: String,
        val title: String,
        val summary: String,
        val detail: String,
        val sizeLabel: String,
        val fileName: String,
        val url: String,
        val bytes: Long,
    )

    /**
     * The models on offer.
     *
     * Two models, each in two sizes: the everyday one, which takes the lead
     * voice out and leaves the rest of the song alone, and the karaoke one,
     * which takes the lead voice out but leaves backing vocals and harmonies
     * in the music.
     *
     * The smaller file of each pair holds the same weights in half precision.
     * It is the same network and the same answer — measured against a
     * reference mixture, the half-precision model separates as well as the
     * full one to five decimal places (vocal correlation 0.99774 either way)
     * — and it is half the download and a little faster, so it is the one
     * offered first.
     */
    val catalogue: List<Model> = listOf(
        Model(
            id = "uvr_mdxnet_1_9703_fp16",
            title = "Studio — clean vocals (light)",
            summary = "Takes the lead voice out and leaves the rest of the song alone.",
            detail = "The everyday choice. Works on most songs. About 15 MB, downloaded once.",
            sizeLabel = "15 MB",
            fileName = "UVR_MDXNET_1_9703_fp16.onnx",
            url = "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/UVR_MDXNET_1_9703_fp16.onnx",
            bytes = 14_871_124L,
        ),
        Model(
            id = "uvr_mdxnet_1_9703",
            title = "Studio — clean vocals (full precision)",
            summary = "Takes the lead voice out and leaves the rest of the song alone.",
            detail = "The same network with every weight written out in full. " +
                "About 30 MB, downloaded once.",
            sizeLabel = "30 MB",
            fileName = "UVR_MDXNET_1_9703.onnx",
            url = "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/UVR_MDXNET_1_9703.onnx",
            bytes = 29_704_436L,
        ),
        Model(
            id = "uvr_mdxnet_kara_fp16",
            title = "Studio — keeps harmonies (light)",
            summary = "Takes the lead voice out but leaves backing voices and harmonies in.",
            detail = "For songs where the backing vocals are part of the music. " +
                "About 15 MB, downloaded once.",
            sizeLabel = "15 MB",
            fileName = "UVR_MDXNET_KARA_fp16.onnx",
            url = "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/UVR_MDXNET_KARA_fp16.onnx",
            bytes = 14_871_426L,
        ),
        Model(
            id = "uvr_mdxnet_kara",
            title = "Studio — keeps harmonies (full precision)",
            summary = "Takes the lead voice out but leaves backing voices and harmonies in.",
            detail = "The same network with every weight written out in full. " +
                "About 28 MB, downloaded once.",
            sizeLabel = "28 MB",
            fileName = "UVR_MDXNET_KARA.onnx",
            url = "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/UVR_MDXNET_KARA.onnx",
            bytes = 29_704_738L,
        ),
    )

    fun byId(id: String?): Model? = catalogue.firstOrNull { it.id == id }

    /** The model used when nothing has been chosen yet. */
    val default: Model get() = catalogue.first()
}

/**
 * Downloads, keeps and hands out the karaoke models.
 *
 * The state is a map from model id to what is happening with that model, so
 * the settings screen and the download popup read the same thing and cannot
 * disagree about it.
 */
object SingModelManager {

    private const val TAG = "SingModelManager"
    private const val DIRECTORY = "sing_models"
    private const val PREFS = "bitchord_sing_models"
    private const val KEY_SELECTED = "selected_model"

    /**
     * The suffix of the file the separator opens.
     *
     * It carries the version of the rewrite, because the rewrite has been
     * wrong once: the files with the older suffix are the published models
     * with their time axis still frozen at 256 frames, which is a model the
     * separator cannot use at the window it asks for. The download itself
     * is still on the phone, so those are redone rather than fetched again.
     */
    private const val PREPARED_SUFFIX = ".frames2"

    /** The suffix the broken rewrite wrote. */
    private const val STALE_PREPARED_SUFFIX = ".frames"

    /** What is happening with one model. */
    sealed interface State {
        /** Not on the phone. */
        data object Absent : State
        /** On the phone, ready to use. */
        data object Ready : State
        /** Being fetched. */
        data class Downloading(val fraction: Float) : State
        /** The fetch failed. */
        data class Failed(val message: String) : State
        /** Being prepared for use -- the model is there but its frame dimension is being rewritten. */
        data object Preparing : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    @Volatile
    private var context: Context? = null

    /** Must be called before anything else here. */
    fun install(context: Context) {
        this.context = context.applicationContext
        _selected.value = prefs().getString(KEY_SELECTED, null)
        refreshAll()
        // A model prepared by an older build may not be a model the
        // separator can use: the rewrite that frees the time axis was
        // broken, so those files are the published models with 256 frames
        // still frozen into them. The download is still on the phone, so
        // the rewrite is done again here rather than fetched again.
        scope.launch { reprepare() }
    }

    /**
     * Rewrites every model that has a download but no usable copy.
     *
     * No network: the file is already here, and the rewrite is a byte
     * edit rather than a download.
     */
    private suspend fun reprepare() = withContext(Dispatchers.IO) {
        for (model in SingModels.catalogue) {
            if (preparedFile(model).exists()) continue
            if (!downloadFile(model).exists()) continue
            publish(model.id, State.Preparing)
            prepare(model)
        }
    }

    private fun prefs() =
        (context ?: error("SingModelManager.install() has not been called"))
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun directory(): File =
        File((context ?: error("SingModelManager.install() has not been called")).filesDir, DIRECTORY)
            .also { if (!it.exists()) it.mkdirs() }

    /** The file a model is downloaded to. */
    fun downloadFile(model: SingModels.Model): File = File(directory(), model.fileName)

    /**
     * The file the separator should actually open.
     *
     * The downloaded model is rewritten once, when it arrives, so its time
     * dimension is no longer frozen at 256 frames. The rewritten copy is
     * what the separator reads; the original is kept so the rewrite can be
     * redone if the front end ever changes.
     */
    fun preparedFile(model: SingModels.Model): File =
        File(directory(), model.fileName + PREPARED_SUFFIX)

    /** Whether a model is on the phone and ready to use. */
    fun isReady(model: SingModels.Model): Boolean = preparedFile(model).exists()

    /** The model the listener has chosen, if it is ready. */
    fun selectedModel(): SingModels.Model? {
        val id = _selected.value ?: return null
        val model = SingModels.byId(id) ?: return null
        return model.takeIf { isReady(it) }
    }

    /** The file to open for the selected model, or null when there is none. */
    fun selectedFile(): File? = selectedModel()?.let { preparedFile(it) }

    /** Refreshes what is on the phone, for the settings screen. */
    fun refreshAll() {
        val next = HashMap<String, State>()
        for (model in SingModels.catalogue) {
            next[model.id] = when {
                jobs[model.id]?.isActive == true ->
                    _states.value[model.id] ?: State.Downloading(0f)
                isReady(model) -> State.Ready
                downloadFile(model).exists() -> State.Preparing
                else -> State.Absent
            }
        }
        _states.value = next
    }

    /** Picks the model to use. Does nothing if it is not on the phone. */
    fun select(model: SingModels.Model) {
        if (!isReady(model)) return
        _selected.value = model.id
        prefs().edit().putString(KEY_SELECTED, model.id).apply()
    }

    /**
     * Fetches a model and prepares it for use.
     *
     * The download is written straight to its final name and the rewrite
     * happens afterwards, so an interrupted download leaves a partial file
     * that is recognised as partial and cleaned up rather than mistaken for
     * a model.
     */
    fun download(model: SingModels.Model) {
        val app = context ?: return
        jobs[model.id]?.cancel()
        _states.value = _states.value + (model.id to State.Downloading(0f))
        jobs[model.id] = scope.launch {
            try {
                val target = downloadFile(model)
                val partial = File(target.parentFile, target.name + ".part")
                partial.delete()

                val request = Request.Builder().url(model.url).build()
                com.music.bitchord.data.Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty response")
                    val total = body.contentLength().takeIf { it > 0 } ?: model.bytes
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                publish(model.id, State.Downloading((copied.toFloat() / total).coerceIn(0f, 1f)))
                            }
                        }
                    }
                }

                publish(model.id, State.Preparing)
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                prepare(model)

                if (_selected.value == null) select(model)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "model download failed: ${failure.message}", failure)
                publish(model.id, State.Failed(failure.message ?: "download failed"))
            }
        }
    }

    /** Stops a download in progress. */
    fun cancel(model: SingModels.Model) {
        jobs[model.id]?.cancel()
        jobs.remove(model.id)
        File(directory(), model.fileName + ".part").delete()
        refreshAll()
    }

    /** Removes a model from the phone. */
    fun delete(model: SingModels.Model) {
        cancel(model)
        downloadFile(model).delete()
        preparedFile(model).delete()
        File(directory(), model.fileName + STALE_PREPARED_SUFFIX).delete()
        if (_selected.value == model.id) {
            _selected.value = null
            prefs().edit().remove(KEY_SELECTED).apply()
        }
        refreshAll()
    }

    /**
     * Rewrites the downloaded model's frame dimension.
     *
     * A model that cannot be rewritten still works -- at 256 frames, which
     * costs five times the latency and five times the work -- so a failure
     * here is not fatal. The rewritten copy is only used when it exists.
     */
    private suspend fun prepare(model: SingModels.Model) = withContext(Dispatchers.IO) {
        val source = downloadFile(model)
        val prepared = preparedFile(model)
        if (!source.exists()) return@withContext
        val patched = runCatching {
            OnnxFramePatch.makeFramesDynamic(source.readBytes())
        }.getOrNull()
        if (patched != null) {
            prepared.writeBytes(patched)
            // The copy the broken rewrite made is the published model with
            // its time axis frozen: thirty megabytes of a file that cannot
            // be used, and no reason to keep it.
            File(directory(), model.fileName + STALE_PREPARED_SUFFIX).delete()
        } else {
            // Fall back to the model as published, by linking the original
            // into place so the separator has one path to open.
            source.copyTo(prepared, overwrite = true)
        }
        publish(model.id, State.Ready)
    }

    private fun publish(id: String, state: State) {
        _states.value = _states.value + (id to state)
    }
}
