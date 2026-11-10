package com.music.bitchord.data.sources.module

import android.util.Log
import com.music.bitchord.data.TrackLog
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Sandboxed QuickJS engine pool for executing module JS.
 *
 * Modules commonly stash session/auth state in top-level variables set up
 * once at load time (e.g. an eager token pre-fetch). The old one-shot
 * executor re-evaluated the whole script on every function call, so that
 * state never survived from `searchTracks()` to `getStreamUrl()` — each
 * ran in its own throwaway VM. Keeping one engine alive per loaded module,
 * reused across calls, is what a normal module host does and what these
 * modules assume. LRU-capped at [MAX_MODULES]; least-recently-used is
 * evicted to make room rather than refusing to load past the cap.
 *
 * ### Why a module gets several engines and not one
 *
 * A QuickJS VM is a single-threaded interpreter and [callExport] drives it
 * across two `evaluate` calls with the answer parked in a global in between.
 * Both of those make one engine strictly one call at a time: two callers
 * sharing it would interleave their `evaluate`s inside the interpreter, and
 * even if they didn't, the second would overwrite `__spine_resolved_json`
 * before the first had read it — so one track's download would quietly receive
 * another track's stream URL. That was survivable while downloads drained one
 * at a time and playback asked for one track at a time; it is not survivable
 * with several downloads in flight.
 *
 * Serialising on a single engine would be correct and would also give all the
 * concurrency back, since a module's own HTTP fetches happen *inside* the VM
 * and hold it for their whole duration — measured at 7.7s for one module on
 * one query. So each module gets a small pool instead: [ENGINES_PER_MODULE]
 * independent VMs, each handed to one caller at a time, growing lazily so a
 * module nobody is hammering still costs exactly one.
 *
 * Ported from Convx's `QuickJsExecutor`, with the pool added.
 */
internal object QuickJsExecutor {

    private const val TAG = "BitChord"

    /**
     * How many modules stay resident.
     *
     * Has to cover a whole index, not a working set. A search fans out to
     * *every* module at once, so a cap below the index size means each search
     * evicts engines the same search is still using — and the eviction is
     * invisible to [ModuleManager], which caches the load separately and goes
     * on reporting a hit for an engine that is gone. What that produced was
     * `Module … is not loaded` on the stream call for whichever modules lost
     * the race, one wasted attempt per track, on every track. Four was the
     * ported default and is below the size of a real index.
     */
    private const val MAX_MODULES = 12

    /**
     * How many callers one module can serve at once.
     *
     * Each is a whole interpreter with the module's script evaluated into it,
     * so this is not free — and each costs whatever the module does at load
     * time, which for several of them is an eager token fetch. Three is sized
     * against the download queue's own width: enough that the workers are not
     * queueing behind each other on the one slow module in the index, few
     * enough that a three-module index is nine VMs and not thirty.
     */
    private const val ENGINES_PER_MODULE = 3

    /**
     * One module's interpreters, and the source to make more from.
     *
     * [free] is the ones nobody is inside right now. It is unbounded and only
     * ever holds engines this pool made, so a send to it cannot fail or block;
     * a receive is a caller waiting its turn.
     */
    private class Pool(
        val moduleId: String,
        val jsCode: String,
        val fetchBase: String,
        val acceptRegistered: Boolean,
        /** Host globals evaluated before the module's own code. See [loadModule]. */
        val preamble: String,
        /** Code evaluated after it, to hand back the API it registered. */
        val epilogue: String,
        /** The only hosts this module may fetch, or null for no restriction. */
        val allowedHosts: List<String>?,
        /** The host's `sessionCall`, or null when signed sessions are not offered. */
        val sessionCall: ((String, String) -> String)?,
    ) {
        val free = Channel<QuickJs>(Channel.UNLIMITED)
        val lock = Mutex()
        /** Engines made or being made — the claim that stops two callers both growing the pool. */
        var started = 0
        /** Engines actually made, for closing them on unload. Guarded by [lock]. */
        val made = mutableListOf<QuickJs>()
        /**
         * Set when this module is being unloaded.
         *
         * A QuickJS engine cannot be closed while another thread is executing
         * inside it, and closing one that is checked out is not a slow close —
         * it is a hang that takes the calling thread with it. That is what made
         * uninstalling an extension freeze the app: the extension was mid-search
         * (playback retries it every couple of seconds) when its engine was
         * closed from the settings sheet. The free engines are closed here; the
         * one in flight closes itself when it comes back.
         */
        @Volatile
        var retired = false
    }

    /** Whether [moduleId] still has a live pool, i.e. hasn't been evicted. */
    fun isLoaded(moduleId: String): Boolean =
        synchronized(engineLock) { pools.containsKey(moduleId) }

    private val syncHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val engineLock = Any()

    /** LRU map: access-ordered so the oldest-used entry is first. */
    private val pools = LinkedHashMap<String, Pool>(16, 0.75f, true)

    /**
     * Where a module's `storage` writes go.
     *
     * Installed once at start up — the extension host points it at a real
     * directory; a preview run or a test leaves the in-memory default, so
     * nothing written there can outlive the process that wrote it.
     */
    @Volatile
    var storage: ModuleStorage = MemoryModuleStorage()

    // ── Load ─────────────────────────────────────────────────────────────

    suspend fun loadModule(
        moduleId: String,
        jsCode: String,
        fetchBase: String = "",
        /**
         * The extension host's signed-session bridge, when it has one.
         *
         * Called as `__spine.sessionCall(method, argsJson)` from JavaScript and
         * answered with JSON text. Synchronous by design: the extension's
         * `session.*` functions are synchronous in Mornye's host too, and an
         * extension that had to await them was never written.
         */
        sessionCall: ((String, String) -> String)? = null,
        /**
         * Whether a module that registers itself instead of filling
         * `module.exports` counts as loaded.
         *
         * Auris's own modules are CommonJS: they export `searchTracks` or
         * `getTrackStreamUrl`, and the check below is what tells a working
         * module from one that failed to initialise.
         *
         * Mornye `.sflx` extensions are not: they call `registerExtension({…})`
         * and hang their API off a global — see [SflxShim] — because that is the
         * contract their host defined. Both shapes land in the same place,
         * `__spine_mod`, so the difference is one flag rather than a second
         * executor.
         */
        acceptRegistered: Boolean = false,
        /**
         * JavaScript evaluated in the engine *before* the module's own code —
         * the host globals an extension was written against.
         *
         * Empty for this app's own modules, which assume nothing is installed
         * for them. Non-empty for a Mornye extension, whose whole API is built
         * on globals its host was expected to have defined first; see
         * [SflxShim].
         */
        preamble: String = "",
        /**
         * JavaScript evaluated *after* it, inside the same closure.
         *
         * The counterpart to [preamble]: an extension registers itself rather
         * than exporting anything, so something has to run afterwards and put
         * that registration where the sandbox's call path looks — `module
         * .exports`. Empty for a module that exports its own API.
         */
        epilogue: String = "",
        /**
         * The only host names the module may fetch, or null to allow any.
         *
         * Enforced in the fetch binding rather than in the JavaScript, because
         * this is a permission and not a convenience: an extension declares the
         * hosts it needs in its manifest, the listener installs it on the
         * strength of that list, and a check performed by the code being
         * checked is not a check.
         *
         * Matched exactly, or by a leading `*.` for a subdomain — the same rule
         * the manifest's own parser documents, and deliberately not "any
         * subdomain of" for a bare domain, which would let `*.example.com`
         * match `evil-example.com`.
         */
        allowedHosts: List<String>? = null,
    ): Result<Unit> {
        val evicted = synchronized(engineLock) {
            if (pools.containsKey(moduleId)) {
                TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) — ENGINE CACHE HIT")
                return Result.success(Unit)
            }
            buildList {
                while (pools.size >= MAX_MODULES) {
                    val lruId = pools.keys.firstOrNull() ?: break
                    TrackLog.d(TAG, "  Evicting LRU module engine: $lruId")
                    pools.remove(lruId)?.let { addAll(it.made) }
                }
            }
        }
        evicted.forEach { runCatching { it.close() } }

        TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) fetchBase=$fetchBase jsCodeLength=${jsCode.length}")
        return withContext(Dispatchers.Default) {
            runCatching { newEngine(moduleId, jsCode, fetchBase, acceptRegistered, preamble, epilogue, allowedHosts, sessionCall) }
                .onSuccess { qjs ->
                    // The pool starts at one and grows only if two callers ever
                    // want this module at the same moment. A module that is
                    // never contended never pays for a second interpreter.
                    val pool = Pool(moduleId, jsCode, fetchBase, acceptRegistered, preamble, epilogue, allowedHosts, sessionCall)
                    pool.started = 1
                    pool.made += qjs
                    pool.free.trySend(qjs)
                    synchronized(engineLock) { pools[moduleId] = pool }
                }
                .onFailure { TrackLog.e(TAG, "  ✗ loadModule FAILED for $moduleId: ${it.message}", it) }
                .map { }
        }
    }

    /** A fresh interpreter with [jsCode] evaluated into it, or a throw saying why not. */
    private suspend fun newEngine(
        moduleId: String,
        jsCode: String,
        fetchBase: String,
        acceptRegistered: Boolean,
        preamble: String,
        epilogue: String,
        allowedHosts: List<String>?,
        sessionCall: ((String, String) -> String)?,
    ): QuickJs {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 512 * 1024L
        try {
            bindConsole(qjs)
            bindAsyncFetch(qjs, moduleId, fetchBase, allowedHosts, sessionCall)

            qjs.evaluate<String>(POLYFILLS)
            val cleanCode = preprocessModuleCode(jsCode)

            qjs.evaluate<String>(
                """
                var __spine_iife_error = null;
                var __spine_accept_registered = ${if (acceptRegistered) "true" else "false"};
                var __spine_mod = (function() {
                    try {
                        var module = { exports: {} };
                        var exports = module.exports;
                        var self = {};
                        $preamble
                        $cleanCode
                        $epilogue
                        if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                            return module.exports;
                        }
                        // An extension that registered itself: its API is the
                        // global object it handed over, which is the contract
                        // Mornye's own host reads. See [loadModule]'s note.
                        if (__spine_accept_registered && typeof globalThis.extension === 'object'
                            && globalThis.extension) {
                            return globalThis.extension;
                        }
                        return {};
                    } catch(e) {
                        __spine_iife_error = e && e.message ? e.message : String(e);
                        return {};
                    }
                })();
                'ok'
                """.trimIndent()
            )

            val iifeError = qjs.evaluate<String>("__spine_iife_error || 'none'")
            if (iifeError != "none") throw IllegalStateException("Module init error: $iifeError")

            val keys = qjs.evaluate<String>("Object.keys(__spine_mod).join(', ')")
            TrackLog.d(TAG, "  Module exports: [$keys]")
            return qjs
        } catch (e: Throwable) {
            qjs.close()
            throw e
        }
    }

    // ── Pool ─────────────────────────────────────────────────────────────

    /**
     * An interpreter of [moduleId]'s, exclusively, until it is given back.
     *
     * Takes a free one if there is one, makes another if the pool has room, and
     * otherwise waits for whoever is using one to finish. The claim on a new
     * engine is taken *before* it is built and released if building throws, so
     * a module whose script fails to evaluate cannot permanently consume a slot
     * in the pool it never joined.
     */
    private suspend fun acquire(moduleId: String, pool: Pool): QuickJs {
        pool.free.tryReceive().getOrNull()?.let { return it }

        val mine = pool.lock.withLock {
            if (pool.started >= ENGINES_PER_MODULE) return@withLock false
            pool.started++
            true
        }
        if (!mine) return pool.free.receive()

        return try {
            newEngine(
                pool.moduleId,
                pool.jsCode,
                pool.fetchBase,
                pool.acceptRegistered,
                pool.preamble,
                pool.epilogue,
                pool.allowedHosts,
                pool.sessionCall,
            ).also { fresh ->
                pool.lock.withLock { pool.made += fresh }
            }
        } catch (e: Throwable) {
            pool.lock.withLock { pool.started-- }
            throw e
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────

    suspend fun callExport(moduleId: String, functionName: String, args: List<String>): Result<String> {
        val pool = synchronized(engineLock) { pools[moduleId] }
            ?: return Result.failure(IllegalStateException("Module $moduleId is not loaded"))

        // The trailing argument is the module's context object, which carries
        // the app identity key. It is never logged — not the whole argument
        // list, and not a truncated prefix that could still contain it. What
        // was called and with which query/trackId is logged by ModuleManager.
        TrackLog.d(TAG, "QuickJsExecutor.callExport($moduleId, $functionName)")
        return withContext(Dispatchers.Default) {
            val qjs = try {
                acquire(moduleId, pool)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext Result.failure(e)
            }
            try {
                callOn(qjs, moduleId, functionName, args)
            } finally {
                // Back into circulation on every path, including a throw: an
                // engine that is never returned is a slot the pool can never
                // hand out again, and three of those would deadlock the module.
                // A retired pool's engine is closed instead — here, on the
                // thread that was using it, which is the only thread that may.
                if (pool.retired) {
                    runCatching { qjs.close() }
                } else {
                    pool.free.trySend(qjs)
                }
            }
        }
    }

    private suspend fun callOn(
        qjs: QuickJs,
        moduleId: String,
        functionName: String,
        args: List<String>,
    ): Result<String> =
        run {
            runCatching {
                val hasFn = qjs.evaluate<String>("typeof __spine_mod['$functionName']")
                if (hasFn != "function") {
                    throw IllegalStateException("$functionName is not a function on module $moduleId")
                }

                val argsStr = args.joinToString(",")

                // evaluate() converts a Promise via toString() instead of awaiting it.
                // Workaround: store the resolved JSON in a global var inside the async
                // IIFE, then read it in a second evaluate() call.
                qjs.evaluate<String>(
                    """
                    var __spine_resolved_json = undefined;
                    (async function() {
                        var __fn = __spine_mod['$functionName'];
                        if (!__fn) {
                            __spine_resolved_json = JSON.stringify({ error: '$functionName not found' });
                            return;
                        }
                        try {
                            var r = await __fn($argsStr);
                            __spine_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
                        } catch(e) {
                            __spine_resolved_json = JSON.stringify({ error: e && e.message ? e.message : String(e) });
                        }
                    })();
                    """.trimIndent()
                )

                val rawResult = qjs.evaluate<String>("__spine_resolved_json")
                TrackLog.d(TAG, "  callExport result (${rawResult.length} chars): ${rawResult.take(500)}")
                rawResult
            }.onFailure {
                TrackLog.e(TAG, "  ✗ callExport FAILED for $moduleId.$functionName: ${it.message}", it)
            }
        }

    // ── Unload ───────────────────────────────────────────────────────────

    fun unload(moduleId: String) {
        val pool = synchronized(engineLock) { pools.remove(moduleId) } ?: return
        pool.retired = true
        // Only the engines nobody is inside. The rest close themselves as they
        // are handed back — see the `finally` in [callExport].
        var closed = 0
        while (true) {
            val engine = pool.free.tryReceive().getOrNull() ?: break
            runCatching { engine.close() }
            closed++
        }
        TrackLog.d(TAG, "QuickJsExecutor.unload($moduleId) (closed $closed idle engines)")
    }

    fun unloadAll() {
        val all = synchronized(engineLock) {
            val values = pools.values.flatMap { it.made }
            pools.clear()
            values
        }
        all.forEach { runCatching { it.close() } }
        TrackLog.d(TAG, "QuickJsExecutor.unloadAll()")
    }

    // ── Code pre-processing ──────────────────────────────────────────────

    /**
     * Strips ES-module `export` keywords so the code runs inside an IIFE
     * that wraps it in a CommonJS-style `module.exports` object.
     *
     * Also handles the template-literal export format some Spine sources use:
     * `export const x = \`…actual JS…\``
     */
    private fun preprocessModuleCode(jsCode: String): String {
        val code = jsCode.trim()

        val exportPattern = Regex("""^export\s+const\s+\w+\s*=\s*`""")
        val exportMatch = exportPattern.find(code)
        if (exportMatch != null) {
            val contentStart = exportMatch.range.last + 1
            var i = contentStart
            while (i < code.length) {
                if (code[i] == '\\' && i + 1 < code.length) { i += 2; continue }
                if (code[i] == '`') {
                    return code.substring(contentStart, i).trim()
                }
                i++
            }
            TrackLog.w(TAG, "  Template literal not closed, falling through to regex preprocess")
        }

        var result = code
        result = result.replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
        result = result.replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
        result = result.replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        return result
    }

    // ── Console binding ──────────────────────────────────────────────────

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.d(TAG, "[JS] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("error", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.e(TAG, "[JS-ERR] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("warn", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.w(TAG, "[JS-WARN] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("info", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.i(TAG, "[JS-INFO] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
        }
    }

    // ── Fetch binding ────────────────────────────────────────────────────

    private suspend fun bindAsyncFetch(
        qjs: QuickJs,
        moduleId: String,
        fetchBase: String,
        allowedHosts: List<String>?,
        sessionCall: ((String, String) -> String)? = null,
    ) {
        qjs.define("__spine") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val rawUrl = args[0]?.toString()
                        ?: throw IllegalArgumentException("fetch requires a URL")
                    val method = args[1]?.toString() ?: "GET"
                    val headersJson = args[2]?.toString() ?: "{}"
                    val body = args[3]?.toString()
                    val url = resolveUrl(rawUrl, fetchBase)

                    if (!mayReach(url, allowedHosts)) {
                        // Answered in the response shape rather than thrown, for
                        // the same reason the network error below is: an
                        // extension checks `response.ok`, and a refusal it can
                        // read is one it can report. A thrown exception here
                        // would surface as a stack trace inside its own code.
                        TrackLog.w(TAG, "  ✗ fetch refused for $moduleId: $url is not in its permissions")
                        return JSONObject().apply {
                            put("status", 0)
                            put("ok", false)
                            put("body", "Blocked: $url is not permitted by this extension's manifest")
                            put("headers", JSONObject())
                        }.toString()
                    }

                    TrackLog.d(TAG, "  → fetch $method ${redactModuleUrl(url)}")
                    val result = fetchUrlSync(url, method, headersJson, body)
                    TrackLog.d(TAG, "    HTTP ${result.code} (${result.body.length} bytes)")

                    val respObj = JSONObject().apply {
                        put("status", result.code)
                        put("ok", result.code in 200..299)
                        put("body", result.body)
                        put("headers", result.headers)
                    }
                    return respObj.toString()
                }
            })
            // ── Persistent state ─────────────────────────────────────────
            // Synchronous by contract: extensions call `storage.get(key)` and
            // use the answer inline, so this cannot be a promise. The call runs
            // on the interpreter's own worker thread, never the main thread —
            // see [ModuleStorage].
            function("storageGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val key = args.getOrNull(0)?.toString() ?: return "null"
                    val value = storage.get(moduleId, key) ?: return "null"
                    // Returned as a JSON string literal so the JavaScript side
                    // can tell "the text is the word null" from "there is
                    // nothing stored" — the two would otherwise be the same
                    // value and an extension would lose its session on the
                    // first run after someone stored the string "null".
                    return JSONObject.quote(value)
                }
            })
            function("storageSet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val key = args.getOrNull(0)?.toString() ?: return "error"
                    val value = args.getOrNull(1)?.toString() ?: return "error"
                    return runCatching { storage.set(moduleId, key, value) }
                        .fold(onSuccess = { "ok" }, onFailure = { "error" })
                }
            })
            function("storageRemove", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val key = args.getOrNull(0)?.toString() ?: return "error"
                    return runCatching { storage.remove(moduleId, key) }
                        .fold(onSuccess = { "ok" }, onFailure = { "error" })
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val ms = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
                    kotlinx.coroutines.delay(ms)
                    return "0"
                }
            })
            asyncFunction("clearTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String = "ok"
            })
            // ── Signed sessions ──────────────────────────────────────────
            // `sessionCall(method, argsJson)` is the whole bridge: Mornye's
            // `session.*` are thin wrappers over exactly this, and answering in
            // JSON keeps the shape of every result in one place (the Kotlin
            // client) rather than split across two languages.
            if (sessionCall != null) {
                function("sessionCall", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String {
                        val method = args.getOrNull(0)?.toString().orEmpty()
                        val payload = args.getOrNull(1)?.toString().orEmpty().ifBlank { "[]" }
                        return runCatching { sessionCall(method, payload) }
                            .getOrElse {
                                TrackLog.w(TAG, "  ✗ sessionCall($method) failed: ${it.message}")
                                JSONObject()
                                    .put("success", false)
                                    .put("ok", false)
                                    .put("error", it.message ?: "session call failed")
                                    .toString()
                            }
                    }
                })
            }
            // ── Synchronous HTTP ─────────────────────────────────────────
            // Mornye's `http.*` are synchronous: an extension written against
            // that host reads `http.get(url).status` without awaiting it, and a
            // promise here is `undefined` — which is exactly the
            // "customSearch failed: HTTP undefined" every Tidal search produced
            // while the fetch itself was returning 200 with 16 KB of JSON.
            function("httpSync", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val rawUrl = args.getOrNull(0)?.toString().orEmpty()
                    if (rawUrl.isEmpty()) {
                        return JSONObject()
                            .put("ok", false).put("status", 0).put("body", "")
                            .put("headers", JSONObject()).put("error", "URL is required")
                            .toString()
                    }
                    val method = args.getOrNull(1)?.toString()?.ifBlank { "GET" } ?: "GET"
                    val headersJson = args.getOrNull(2)?.toString().orEmpty().ifBlank { "{}" }
                    val body = args.getOrNull(3)?.toString()?.takeIf { it != "null" }
                    val url = resolveUrl(rawUrl, fetchBase)
                    if (!mayReach(url, allowedHosts)) {
                        return JSONObject()
                            .put("ok", false).put("status", 0).put("body", "")
                            .put("headers", JSONObject())
                            .put("error", "Blocked: $url is not permitted by this extension's manifest")
                            .toString()
                    }
                    return runCatching {
                        val result = fetchUrlSync(url, method, headersJson, body)
                        JSONObject()
                            .put("ok", result.code in 200..299)
                            .put("status", result.code)
                            .put("body", result.body)
                            .put("headers", result.headers)
                            .toString()
                    }.getOrElse { error ->
                        TrackLog.w(TAG, "  ✗ httpSync $method ${redactModuleUrl(url)} failed: ${error.message}")
                        JSONObject()
                            .put("ok", false).put("status", 0).put("body", "")
                            .put("headers", JSONObject())
                            .put("error", error.message ?: "request failed")
                            .toString()
                    }
                }
            })
            // ── Digests ──────────────────────────────────────────────────
            // `utils.sha256` and friends are host functions in Mornye, and three
            // extensions sign their own requests with them. Doing the digests
            // here rather than in JavaScript is not a shortcut: a JS SHA-256
            // costs more tokens than this whole file, and a wrong one is silent.
            function("md5", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    hexDigest("MD5", args.getOrNull(0)?.toString().orEmpty())
            })
            function("sha256", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    hexDigest("SHA-256", args.getOrNull(0)?.toString().orEmpty())
            })
            function("hmacSHA256", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    // Mornye's own order: message first, key second.
                    val message = args.getOrNull(0)?.toString().orEmpty()
                    val key = args.getOrNull(1)?.toString() ?: return ""
                    return hexDigest("HmacSHA256", message, key)
                }
            })
            function("hmacSHA256Base64", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val message = args.getOrNull(0)?.toString().orEmpty()
                    val key = args.getOrNull(1)?.toString() ?: return ""
                    return runCatching {
                        java.util.Base64.getEncoder()
                            .encodeToString(mac("HmacSHA256", key.toByteArray()).doFinal(message.toByteArray()))
                    }.getOrDefault("")
                }
            })
        }

        // Expose a web-compatible fetch() surface over the native binding.
        qjs.evaluate<Unit>(
            """
            var fetch = async function(url, options) {
                var method = 'GET';
                var headers = '{}';
                var body = null;
                if (options) {
                    method = options.method || 'GET';
                    if (options.headers) {
                        if (typeof options.headers === 'string') {
                            headers = options.headers;
                        } else {
                            try { headers = JSON.stringify(options.headers); } catch(e) { headers = '{}'; }
                        }
                    }
                    if (options.body !== undefined && options.body !== null) {
                        body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                    if (options.signal && options.signal.aborted) {
                        throw new Error('Aborted');
                    }
                }
                var raw = JSON.parse(await __spine.fetch(url, method, headers, body));
                var respBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() { try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON: ' + respBody.substring(0, 200)); } },
                    text: function() { return respBody; },
                    arrayBuffer: function() { throw new Error('Not implemented'); },
                    clone: function() { return this; },
                    headers: { get: function(k) {
                        if (k == null) return null;
                        var map = raw.headers || {};
                        var value = map[String(k).toLowerCase()];
                        return value == null ? null : String(value);
                    } }
                };
            };

            var setTimeout = async function(fn, ms) {
                await __spine.setTimeout(null, ms || 0);
                if (typeof fn === 'function') fn();
                return 0;
            };
            var clearTimeout = function(id) {};
            """.trimIndent()
        )
    }

    // ── Digests ──────────────────────────────────────────────────────────

    private fun mac(algorithm: String, key: ByteArray): javax.crypto.Mac =
        javax.crypto.Mac.getInstance(algorithm).apply {
            init(javax.crypto.spec.SecretKeySpec(key, algorithm))
        }

    private fun hexDigest(algorithm: String, text: String, key: String? = null): String = runCatching {
        val bytes = if (key == null) {
            java.security.MessageDigest.getInstance(algorithm).digest(text.toByteArray())
        } else {
            mac(algorithm, key.toByteArray()).doFinal(text.toByteArray())
        }
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    // ── Permissions ──────────────────────────────────────────────────────

    /**
     * Whether a module holding [allowedHosts] may reach [url].
     *
     * Null means "no restriction", which is what this app's own modules get:
     * they are shipped by the app, and the list would be a formality. An
     * extension's list comes from a manifest written by a stranger, so it is
     * enforced.
     *
     * A URL that cannot be parsed is refused rather than allowed: the check
     * exists to stop a request, and "unparseable" is not a reason to let one
     * through. Hosts are compared without case and without a port, because
     * `Example.com:443` and `example.com` are the same host to a server.
     *
     * `*.` is a subdomain wildcard and nothing else — `*.spotify.com` matches
     * `api.spotify.com` and `open.spotify.com` but not `spotify.com` itself,
     * and not `notspotify.com`. That is the manifest format's own rule; see
     * [SflxPermissions.allowsHost].
     */
    private fun mayReach(url: String, allowedHosts: List<String>?): Boolean {
        if (allowedHosts == null) return true
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return false
        return allowedHosts.any { pattern ->
            val allowed = pattern.trim().lowercase(Locale.ROOT)
            when {
                allowed.isEmpty() -> false
                // `*.example.com` is a subdomain wildcard: it must leave the dot
                // in place, or `evilexample.com` would match it.
                allowed.startsWith("*.") -> host.endsWith(allowed.substring(1))
                else -> host == allowed
            }
        }
    }

    // ── URL resolution ───────────────────────────────────────────────────

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (base.isEmpty()) return url
        return if (url.startsWith("/")) {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore("/")
            "$scheme://$host$url"
        } else {
            "$base/$url"
        }
    }

    private data class FetchResult(val code: Int, val body: String, val headers: JSONObject)

    private fun fetchUrlSync(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
    ): FetchResult {
        val builder = Request.Builder().url(url)

        var hasUserAgent = false
        try {
            val headersObj = JSONObject(headersJson)
            for (key in headersObj.keys()) {
                val value = headersObj.optString(key, "")
                builder.header(key, value)
                if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
            }
        } catch (_: Exception) {
        }

        if (!hasUserAgent) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            )
        }

        when (method.uppercase(Locale.ROOT)) {
            "POST" -> builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        syncHttpClient.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            val responseHeaders = JSONObject()
            response.headers.names().forEach { name ->
                responseHeaders.put(
                    name.lowercase(Locale.ROOT),
                    response.headers.values(name).joinToString(", "),
                )
            }
            return FetchResult(response.code, responseBody, responseHeaders)
        }
    }

    // ── Polyfills ────────────────────────────────────────────────────────

    private val POLYFILLS = """
        // The host identity a locked module can read at load time. Other
        // module hosts do not define it, and a plain id is not the lock: the
        // key is only present in this build, which is how an Auris-only
        // module refuses to initialise anywhere else.
        var __spine_app = ${AurisModuleIdentity.json};

        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = { aborted: false }; };
            AbortController.prototype.abort = function() { this.signal.aborted = true; };
        }

        if (typeof Object.assign !== 'function') {
            Object.assign = function(target) {
                if (target == null) throw new TypeError('Cannot convert undefined or null to object');
                var to = Object(target);
                for (var i = 1; i < arguments.length; i++) {
                    var source = arguments[i];
                    if (source != null) {
                        for (var key in source) {
                            if (Object.prototype.hasOwnProperty.call(source, key)) {
                                to[key] = source[key];
                            }
                        }
                    }
                }
                return to;
            };
        }

        if (typeof Promise.any !== 'function') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var errors = [];
                    var remaining = promises.length;
                    if (remaining === 0) { reject(new AggregateError([], 'All promises were rejected')); return; }
                    promises.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            remaining--;
                            if (remaining === 0) reject(new AggregateError(errors, 'All promises were rejected'));
                        });
                    });
                });
            };
        }

        if (typeof Promise.allSettled !== 'function') {
            Promise.allSettled = function(promises) {
                return Promise.all(promises.map(function(p) {
                    return Promise.resolve(p).then(
                        function(value) { return { status: 'fulfilled', value: value }; },
                        function(reason) { return { status: 'rejected', reason: reason }; }
                    );
                }));
            };
        }

        if (typeof AggregateError === 'undefined') {
            var AggregateError = function(errors, message) {
                this.errors = errors;
                this.message = message || '';
                this.name = 'AggregateError';
            };
            AggregateError.prototype = Object.create(Error.prototype);
        }

        if (typeof atob === 'undefined') {
            var atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+${'$'}/, '');
                var output = '';
                for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            };
        }

        if (typeof setTimeout === 'undefined') {
            var __spine_timers = {};
            var __spine_timer_id = 0;
            var setTimeout = function(fn, ms) {
                var id = ++__spine_timer_id;
                __spine_timers[id] = { fn: fn, ms: ms || 0 };
                return id;
            };
            var clearTimeout = function(id) {
                if (__spine_timers[id]) delete __spine_timers[id];
            };
        }
        if (typeof clearTimeout === 'undefined') {
            var clearTimeout = function(id) {
                if (typeof __spine_timers !== 'undefined' && __spine_timers[id]) delete __spine_timers[id];
            };
        }

        if (typeof URL === 'undefined') {
            var URL = function(url, base) {
                this.href = url;
                try {
                    var a = url.replace(/^[^:]+:/, 'http:');
                    var match = a.match(/^\/\/([^/]+)(\/.*)?$/);
                    if (match) {
                        this.hostname = match[1];
                        this.pathname = match[2] || '/';
                    } else {
                        var m2 = a.match(/^https?:\/\/([^/]+)(\/.*)?$/);
                        if (m2) { this.hostname = m2[1]; this.pathname = m2[2] || '/'; }
                    }
                } catch(e) {}
            };
        }
    """
}
