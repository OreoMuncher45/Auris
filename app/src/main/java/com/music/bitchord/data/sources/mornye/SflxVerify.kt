package com.music.bitchord.data.sources.mornye

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
     * A grant from either delivery path.
     *
     * The page posts `target || grant`, where `target` is the deep link and
     * `grant` is the bare token — so both arrive here, and both are accepted.
     */
    private fun deliver(payload: String) {
        val looksLikeUrl = payload.contains("://")
        val grant: String
        val state: String?
        if (looksLikeUrl) {
            val uri = Uri.parse(payload)
            grant = uri.getQueryParameter("grant").orEmpty()
            state = uri.getQueryParameter("state")
        } else {
            grant = payload
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
    }
}
