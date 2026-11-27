package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.web.QuietWebChromeClient
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opening, and finishing, an extension's verification.
 *
 * A signed-session gateway answers "I do not know you" with a challenge URL
 * rather than a session: a page with a captcha, which on success redirects to
 * `mornye://session-grant?grant=…&state=…`. Two consequences decide the shape
 * of this file:
 *
 *  - the page must run **in this app**. The challenge is bound to the cookies
 *    the page itself set, and handing it to the system browser would complete
 *    the captcha in a browser whose cookies the extension exchange never sees.
 *    That is the `lokal` behaviour this mirrors.
 *  - it must be openable **from a background call**. A download asking for a
 *    stream is not a composable and has no activity to show a dialog from, so
 *    the request arrives here as an intent with the host's own application
 *    context.
 *
 * When the grant comes back, [SflxHost.completeGrant] redeems it. A signed call
 * that is waiting for this — see [SflxSignedSession.afterChallenge] — resumes
 * by itself, so the song that triggered the prompt starts when the captcha is
 * solved instead of needing a second press of play.
 */
object SflxVerify {

    private const val TAG = "BitChord"

    @Volatile
    private var appContext: Context? = null

    private val launches = SflxVerificationLaunchGate()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Shows the verification page for [extensionId].
     *
     * Only an exact active duplicate is dropped. A new URL is a new gateway
     * challenge, and another extension is a new request too; [SflxVerifyActivity]
     * is single-task and replaces its content for either rather than stacking a
     * second page. The check and reservation are atomic across signed callers.
     */
    fun open(extensionId: String, displayName: String, url: String) {
        if (url.isBlank()) return
        val context = appContext ?: run {
            Log.w(TAG, "sflx[$extensionId] verification requested before the app context was set")
            return
        }
        val intent = Intent(context, SflxVerifyActivity::class.java)
            .putExtra(SflxVerifyActivity.EXTRA_ID, extensionId)
            .putExtra(SflxVerifyActivity.EXTRA_NAME, displayName)
            .putExtra(SflxVerifyActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launches.open(extensionId, url, launch = {
            Log.d(TAG, "sflx[$extensionId] opening verification")
            context.startActivity(intent)
        }, onFailure = { error ->
            Log.w(TAG, "sflx[$extensionId] could not open verification: ${error.message}")
        })
    }

    /** Called by the page when it closes, so an explicit later request may open it again. */
    fun closed(extensionId: String?, url: String? = null) = launches.closed(extensionId, url)
}

/**
 * One reservation for the verification activity, independent of Android so its
 * concurrency rules can be tested on every JVM. The launch callback runs under
 * the same lock as the reservation: two workers cannot both pass before either
 * has called `startActivity`.
 */
internal class SflxVerificationLaunchGate {
    private data class Challenge(val extensionId: String, val url: String)

    private var showing: Challenge? = null

    @Synchronized
    fun open(
        extensionId: String,
        url: String,
        onFailure: (Throwable) -> Unit = {},
        launch: () -> Unit,
    ) {
        val challenge = Challenge(extensionId, url)
        if (showing == challenge) return
        val previous = showing
        showing = challenge
        try {
            launch()
        } catch (error: Throwable) {
            if (showing == challenge) showing = previous
            onFailure(error)
        }
    }

    @Synchronized
    fun closed(extensionId: String?, url: String? = null) {
        val active = showing ?: return
        if (extensionId == null ||
            (active.extensionId == extensionId && (url == null || active.url == url))
        ) {
            showing = null
        }
    }
}

/**
 * The page itself: a WebView in a dialog, with a title and a close button.
 *
 * Nothing about the challenge is interpreted here. The page is the gateway's,
 * the captcha is the gateway's, and the only thing this app does is notice the
 * grant on its way past.
 */
class SflxVerifyActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var extensionId: String? = null
    private var challengeUrl: String? = null
    private var titleView: TextView? = null
    private var progressView: ProgressBar? = null
    private var pasteButton: Button? = null
    private var pasteStatus: TextView? = null
    private var finished = false

    /**
     * Advances whenever [showChallenge] loads a challenge. An exchange captures
     * it when it starts and only closes the page if it still matches, so a late
     * answer for an earlier challenge can't close the one now on screen.
     */
    private var challengeGeneration = 0

    /**
     * Grants already handed to an exchange. The WebView reports one callback URL
     * through several of its hooks, and the bridge can post the same grant too;
     * a grant is single-use, so only its first delivery is redeemed.
     */
    private val redeeming = java.util.Collections.synchronizedSet(HashSet<String>())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The callback arrives in one of three ways, and all are handled.
        //
        // The verification page's own script tries, in order: a JavaScript
        // bridge named `MornyeGrant` (in-app WebView), a `mornye://` deep link
        // (external browser), and `window.opener` (popup). Chromium silently
        // drops a script-initiated custom-scheme navigation with no user
        // gesture, so the bridge is the only reliable path inside a WebView —
        // and without it the page says "verified", nothing reaches this app,
        // and the extension goes on asking.
        if (handleDeepLink(intent)) return

        val displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 16, 24)
        }
        val title = TextView(this).apply {
            text = if (displayName.isBlank()) "Verify" else "Verify $displayName"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        titleView = title
        bar.addView(title)
        bar.addView(close)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        progressView = progress

        val view = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(true)
            // The page's console stays out of logcat, as on every WebView here.
            webChromeClient = QuietWebChromeClient()
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            // The page's first delivery path. Its script looks for exactly
            // these names, and posts either the bare grant or the whole
            // deep-link URL.
            addJavascriptInterface(GrantBridge(), "MornyeGrant")
            addJavascriptInterface(GrantBridge(), "SpotiflacGrant")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handle(request?.url)

                @Deprecated("Kept for API 21–23 WebViews, which call the String overload")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handle(url?.let { Uri.parse(it) })

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progress.visibility = ProgressBar.GONE
                    // Some pages finish the challenge with `location.replace`
                    // inside a frame or an XHR, where no navigation callback
                    // fires for the URL that matters. Asking where the WebView
                    // ended up catches those.
                    if (url != null) handle(Uri.parse(url))
                    watchForGrant(view)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    // Earlier than `onPageFinished`: a page that solves and
                    // verifies itself within its own load would otherwise
                    // finish before the hook below was in place.
                    watchForGrant(view)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url != null) handle(Uri.parse(url))
                }

            }
        }
        webView = view

        root.addView(bar)
        root.addView(progress)
        root.addView(view)
        root.addView(pasteRow())
        setContentView(root)

        // A floating dialog measures itself WRAP_CONTENT, and a weighted WebView
        // inside that is zero pixels tall — which is what made this page look
        // like an empty black box with a Close button. The page is the point, so
        // the window is given a real size before anything loads.
        val height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)

        showChallenge(intent)
    }

    /** Loads a genuinely new challenge into the one existing verification activity. */
    private fun showChallenge(intent: Intent) {
        extensionId = intent.getStringExtra(EXTRA_ID)
        val displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        challengeUrl = url
        finished = false
        challengeGeneration++
        titleView?.text = if (displayName.isBlank()) "Verify" else "Verify $displayName"
        progressView?.visibility = ProgressBar.VISIBLE
        pasteStatus?.visibility = View.GONE
        if (url.isBlank()) {
            finish()
            return
        }
        webView?.let { view ->
            view.stopLoading()
            view.loadUrl(url)
        }
    }

    /**
     * The manual way out, for when the page cannot hand the grant over.
     *
     * The verification page keeps a copy-link fallback of its own: when it
     * cannot tell that the app took the grant, it shows the callback URL —
     * grant and state included — and offers to copy it. Pasting that here
     * redeems it exactly as the bridge would have.
     *
     * It is here because the automatic path has one dependency this app does
     * not control: the page has to reach its own bridge. A WebView that
     * refuses the script, a captcha that solves in a frame the page cannot
     * read, a browser update that changes the bridge's name — each of those
     * leaves a listener staring at a page that says "verified" and an app
     * that knows nothing. This row is the way out of that, and it costs one
     * text field.
     */
    private fun pasteRow(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 16)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            hint = "Paste the grant or the verification link"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val redeem = Button(this).apply {
            text = "Redeem"
            setOnClickListener {
                val value = field.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) redeemPasted(value)
            }
        }
        // One line under the box: "Redeeming…" while the exchange runs, and the
        // reason when it is refused. Without it a refusal looked like a dead button.
        val status = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
        }
        pasteButton = redeem
        pasteStatus = status
        row.addView(field)
        row.addView(redeem)
        column.addView(row)
        column.addView(status)
        return column
    }

    /**
     * Redeems what the listener pasted, for the extension this page was opened
     * for, and says what happened under the box. See [SflxHost.redeemGrant] for
     * why a pasted link's stale `state` is not a reason to refuse it.
     */
    private fun redeemPasted(value: String) {
        val input = SflxGrantInput.parse(value)
        if (input == null) {
            showPasteStatus("That isn't a code or a verification link.", error = true)
            return
        }
        if (!redeeming.add(input.grant)) return
        pasteButton?.isEnabled = false
        showPasteStatus("Redeeming…", error = false)
        val id = extensionId
        val generation = challengeGeneration
        redeem({ redeemGrant(id, input, typed = true) }) { result ->
            Log.d(TAG, "sflx verification: pasted grant ${if (result.isSuccess) "redeemed" else "refused"}")
            // A refused grant (a dropped connection, a BUSY import) wasn't spent,
            // so it may be tried again whichever challenge is now on screen.
            if (!result.isSuccess) redeeming.remove(input.grant)
            if (generation != challengeGeneration) {
                // Answered for a challenge this page no longer shows.
                pasteButton?.isEnabled = true
                pasteStatus?.visibility = View.GONE
            } else if (result.isSuccess) {
                finishWith(true)
            } else {
                pasteButton?.isEnabled = true
                val reason = result.exceptionOrNull()?.message ?: SflxGrantRefused.REFUSED
                showPasteStatus("Couldn't redeem: ${reason.replaceFirstChar { it.lowercase() }}", error = true)
            }
        }
    }

    private fun showPasteStatus(text: String, error: Boolean) {
        pasteStatus?.apply {
            this.text = text
            setTextColor(if (error) ERROR_TEXT else Color.GRAY)
            visibility = View.VISIBLE
        }
    }

    /**
     * What the verification page posts when it has a grant.
     *
     * Runs on a WebView's own thread, so it touches nothing but the host.
     */
    private inner class GrantBridge {
        @android.webkit.JavascriptInterface
        fun postMessage(payload: String?) {
            val value = payload?.trim().orEmpty()
            if (value.isEmpty()) return
            Log.d(TAG, "sflx verification bridge received a grant")
            deliver(value)
        }
    }

    /**
     * Watches the page's own traffic for the grant.
     *
     * The page posts the grant to the bridge itself — it was written for
     * exactly this host — but that is one delivery path out of three it knows
     * about, and the other two are not available inside a WebView: a
     * script-initiated navigation to `mornye://` is dropped by Chromium when
     * there is no user gesture, and `window.opener` is empty in a tab that
     * opened a page rather than being opened by one.
     *
     * What is left — and what actually happens — is that the page calls
     * `/challenge/verify` itself, with the cookies the captcha left behind,
     * and reads the grant out of the answer. So the grant is watched at the
     * source: this hook wraps `fetch` and `XMLHttpRequest` in the page, and
     * anything that comes back carrying a `grant` is handed to the app. It
     * runs in the page's own origin, which is the only place the answer is
     * readable.
     */
    private fun watchForGrant(view: WebView?) {
        val target = view ?: return
        runCatching { target.evaluateJavascript(GRANT_WATCH, null) }
    }

    /**
     * A grant from the bridge or a deep link, matched to its extension by
     * `state` (pastes go through [redeemPasted]).
     *
     * The page posts `target || grant`, where `target` is the deep link and
     * `grant` is the bare token. [SflxGrantInput] reads both.
     */
    private fun deliver(payload: String) {
        val input = SflxGrantInput.parse(payload) ?: return
        if (!redeeming.add(input.grant)) return
        val generation = challengeGeneration
        redeem({ redeemGrant(null, input, typed = false) }) { result ->
            Log.d(TAG, "sflx verification: grant ${if (result.isSuccess) "redeemed" else "refused"}")
            // A refusal (a dropped connection, say) can be pasted and tried again.
            if (!result.isSuccess) redeeming.remove(input.grant)
            else if (generation == challengeGeneration) finishWith(true)
        }
    }

    private fun redeemGrant(id: String?, input: SflxGrantInput, typed: Boolean): Result<Unit> =
        runCatching {
            SflxCatalog.host?.redeemGrant(id, input.grant, input.state, typed)
                ?: Result.failure(SflxGrantRefused(SflxGrantRefused.NOT_WAITING))
        }.getOrElse { Result.failure(SflxGrantRefused.from(it)) }

    /**
     * Redeems a grant off the main thread, then answers on it.
     *
     * The exchange is a network call (and a wait on a 429), and three of the four
     * delivery paths (the paste box, the deep link and the WebView's navigation
     * callbacks) arrive on the main thread, where Android refuses network I/O.
     * The JS bridge arrives on its own thread, and goes the same way for one rule.
     */
    private fun <T> redeem(exchange: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            done(withContext(Dispatchers.IO) { exchange() })
        }
    }

    /**
     * Whether [uri] is the grant callback.
     *
     * Matched on the shape rather than one hard-coded host, because the scheme
     * and host come from the extension's manifest (`callbackUrl`) and an
     * extension is free to name its own. What is not negotiable is the `grant`
     * and `state` pair.
     */
    private fun handle(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme.orEmpty()
        if (scheme.equals("http", true) || scheme.equals("https", true)) return false
        val grant = uri.getQueryParameter("grant")?.trim().orEmpty()
        if (grant.isEmpty()) return false
        val state = uri.getQueryParameter("state")
        val id = extensionId
        if (id.isNullOrBlank()) {
            deliver(uri.toString())
            return true
        }

        if (!redeeming.add(grant)) return true
        val generation = challengeGeneration
        redeem({ runCatching { SflxCatalog.host?.completeGrant(id, grant, state) == true }.getOrDefault(false) }) { ok ->
            Log.d(TAG, "sflx[$id] verification callback: grant ${if (ok) "redeemed" else "refused"}")
            // A refused grant wasn't spent; free it so a paste can retry it.
            if (!ok) redeeming.remove(grant)
            if (generation == challengeGeneration) finishWith(ok)
        }
        return true
    }

    private fun finishWith(verified: Boolean) {
        if (finished) return
        finished = true
        setResult(if (verified) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleDeepLink(intent)) return
        if (!intent.getStringExtra(EXTRA_URL).isNullOrBlank()) showChallenge(intent)
    }

    /**
     * Redeems a grant delivered as a URL, and answers whether this launch was
     * one — in which case there is no page to show.
     */
    private fun handleDeepLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.getQueryParameter("grant").isNullOrBlank()) return false
        // An outside deep link carries no extension id. It must not wipe the id
        // of the page already open, which the paste box redeems for.
        intent.getStringExtra(EXTRA_ID)?.let { extensionId = it }
        deliver(uri.toString())
        return true
    }

    override fun onDestroy() {
        SflxVerify.closed(extensionId, challengeUrl)
        webView?.let { view ->
            runCatching {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.removeAllViews()
                view.destroy()
            }
        }
        webView = null
        titleView = null
        progressView = null
        pasteButton = null
        pasteStatus = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BitChord"
        const val EXTRA_ID = "sflx.extension.id"
        const val EXTRA_NAME = "sflx.extension.name"
        const val EXTRA_URL = "sflx.verify.url"

        /** Readable red on the page's black, matching the error tone elsewhere. */
        private val ERROR_TEXT = Color.rgb(0xFF, 0x8A, 0x80)

        /**
         * The page-side hook that watches for the grant.
         *
         * Installed in the page's own origin, where the answer to the
         * verification request is readable. It patches the two ways a page
         * asks for something over the network and looks for a `grant` in what
         * comes back; anything it finds is posted to the same bridge the page
         * itself uses.
         *
         * The patch is idempotent — the flag lives on the window, and a new
         * document starts over — because the hook is installed on every page
         * the WebView reaches.
         */
        private val GRANT_WATCH = """
            (function () {
                if (window.__aurisGrantWatch) return;
                window.__aurisGrantWatch = true;
                function report(body) {
                    try {
                        if (!body || typeof body !== 'string') return;
                        var grant = '';
                        try {
                            var parsed = JSON.parse(body);
                            if (parsed && typeof parsed === 'object' && parsed.grant) {
                                grant = String(parsed.grant);
                            }
                        } catch (e) { }
                        if (!grant && body.indexOf('"grant"') >= 0) {
                            var match = body.match(/"grant"\s*:\s*"([^"]+)"/);
                            if (match) grant = match[1];
                        }
                        if (!grant) return;
                        var bridge = window.MornyeGrant || window.SpotiflacGrant;
                        if (bridge && typeof bridge.postMessage === 'function') {
                            bridge.postMessage(grant);
                        }
                    } catch (e) { }
                }
                var originalFetch = window.fetch;
                if (typeof originalFetch === 'function') {
                    window.fetch = function () {
                        var args = arguments;
                        return originalFetch.apply(this, args).then(function (response) {
                            try {
                                if (response && typeof response.clone === 'function') {
                                    response.clone().text().then(report, function () { });
                                }
                            } catch (e) { }
                            return response;
                        });
                    };
                }
                var open = XMLHttpRequest.prototype.open;
                var send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function (method, url) {
                    this.__aurisUrl = url;
                    return open.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { report(this.responseText); } catch (e) { }
                    });
                    return send.apply(this, arguments);
                };
            })();
        """.trimIndent()
    }
}
