package com.music.bitchord.data.sources.mornye

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
import com.music.bitchord.data.DebugLog as Log

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

    /** The prompt already on screen, so three retries do not open three pages. */
    @Volatile
    private var showing: String? = null

    @Volatile
    private var showingAt: Long = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Shows the verification page for [extensionId].
     *
     * Duplicate requests for the same extension within a few seconds are
     * dropped: a gateway that refuses three requests in a row would otherwise
     * stack three identical pages.
     */
    fun open(extensionId: String, displayName: String, url: String) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (showing == extensionId && now - showingAt < DEDUPE_MS) return
        val context = appContext ?: run {
            Log.w(TAG, "sflx[$extensionId] verification requested before the app context was set")
            return
        }
        showing = extensionId
        showingAt = now
        Log.d(TAG, "sflx[$extensionId] opening verification")
        val intent = Intent(context, SflxVerifyActivity::class.java)
            .putExtra(SflxVerifyActivity.EXTRA_ID, extensionId)
            .putExtra(SflxVerifyActivity.EXTRA_NAME, displayName)
            .putExtra(SflxVerifyActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "sflx[$extensionId] could not open verification: ${it.message}") }
    }

    /** Called by the page when it closes, so the next refusal may open a new one. */
    fun closed(extensionId: String?) {
        if (extensionId == null || extensionId == showing) {
            showing = null
            showingAt = 0L
        }
    }

    private const val DEDUPE_MS = 5_000L
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
    private var finished = false

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

        extensionId = intent.getStringExtra(EXTRA_ID)
        val displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

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
        bar.addView(title)
        bar.addView(close)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }

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

        if (url.isBlank()) {
            finish()
            return
        }
        view.loadUrl(url)
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 16)
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
                if (value.isNotEmpty()) deliver(value)
            }
        }
        row.addView(field)
        row.addView(redeem)
        return row
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
     * A grant from any of the delivery paths.
     *
     * The page posts `target || grant`, where `target` is the deep link and
     * `grant` is the bare token; the page's own copy-link fallback shows
     * `grant=…` as text; and a listener may paste any of the three. All of
     * them are read here, because a refusal at this point is a refusal the
     * listener cannot do anything about.
     */
    private fun deliver(payload: String) {
        val value = payload.trim()
        if (value.isEmpty()) return
        val looksLikeUrl = value.contains("://")
        val grant: String
        val state: String?
        if (looksLikeUrl) {
            val uri = Uri.parse(value)
            grant = uri.getQueryParameter("grant").orEmpty()
            state = uri.getQueryParameter("state")
        } else if (value.startsWith("grant=", ignoreCase = true)) {
            // The page's copy-link text: `grant=<token>` on its own.
            grant = value.substringAfter('=').trim()
            state = null
        } else {
            grant = value
            state = null
        }
        if (grant.isBlank()) return
        val ok = runCatching { SflxCatalog.host?.completeGrantForState(grant, state) == true }
            .getOrDefault(false)
        Log.d(TAG, "sflx verification: grant ${if (ok) "redeemed" else "refused"}")
        if (ok) finishWith(true)
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

        val ok = runCatching { SflxCatalog.host?.completeGrant(id, grant, state) == true }
            .getOrDefault(false)
        Log.d(TAG, "sflx[$id] verification callback: grant ${if (ok) "redeemed" else "refused"}")
        finishWith(ok)
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
        handleDeepLink(intent)
    }

    /**
     * Redeems a grant delivered as a URL, and answers whether this launch was
     * one — in which case there is no page to show.
     */
    private fun handleDeepLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.getQueryParameter("grant").isNullOrBlank()) return false
        extensionId = intent.getStringExtra(EXTRA_ID)
        deliver(uri.toString())
        return true
    }

    override fun onDestroy() {
        SflxVerify.closed(extensionId)
        webView?.let { view ->
            runCatching {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.removeAllViews()
                view.destroy()
            }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BitChord"
        const val EXTRA_ID = "sflx.extension.id"
        const val EXTRA_NAME = "sflx.extension.name"
        const val EXTRA_URL = "sflx.verify.url"

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
