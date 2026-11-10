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
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                }
            }
        }
        webView = view

        root.addView(bar)
        root.addView(progress)
        root.addView(view)
        setContentView(root)

        if (url.isBlank()) {
            finish()
            return
        }
        view.loadUrl(url)
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
        if (id.isNullOrBlank()) return false

        val ok = runCatching { SflxCatalog.host?.completeGrant(id, grant, state) == true }
            .getOrDefault(false)
        Log.d(TAG, "sflx[$id] verification callback: grant ${if (ok) "redeemed" else "refused"}")
        if (ok) {
            finishWith(ok)
        } else {
            // A refused grant is worth saying out loud: the page has done its
            // part and the host has not, and silently closing it reads as the
            // captcha having failed.
            finishWith(false)
        }
        return true
    }

    private fun finishWith(verified: Boolean) {
        if (finished) return
        finished = true
        setResult(if (verified) RESULT_OK else RESULT_CANCELED)
        finish()
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
