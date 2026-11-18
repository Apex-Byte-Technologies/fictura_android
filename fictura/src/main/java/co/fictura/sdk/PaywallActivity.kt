package co.fictura.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows a served HTML paywall in a WebView. The page talks to the SDK through the
 * same `window.__FICTURA__` bridge as in the React Native SDK.
 */
class PaywallActivity : Activity(), PaywallHost {
    private var bridge: PaywallBridge? = null
    private var web: WebView? = null
    private var closed = false
    private val main = Handler(Looper.getMainLooper())

    companion object {
        private val pending = ConcurrentHashMap<Int, PaywallBridge>()
        private val open = ConcurrentHashMap<Int, PaywallActivity>()
        const val EXTRA_ID = "co.fictura.paywall.id"

        fun start(activity: Activity, bridge: PaywallBridge) {
            pending[bridge.request.id] = bridge
            activity.startActivity(Intent(activity, PaywallActivity::class.java).putExtra(EXTRA_ID, bridge.request.id))
            if (bridge.request.info.presentationStyle == "no_animation") activity.overridePendingTransition(0, 0)
        }

        fun hide(id: Int) { open.remove(id)?.let { a -> a.runOnUiThread { a.finish() } } }

        val binder: String by lazy {
            PaywallActivity::class.java.classLoader?.getResourceAsStream("fictura_binder.js")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) { main.post { bridge?.handle(message) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val b = pending.remove(id)
        if (b == null) { finish(); return }
        bridge = b
        open[id] = this
        b.host = this
        val root = FrameLayout(this)
        val spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        setContentView(root)
        b.markShown()
        b.loadPackages {
            main.post {
                if (isFinishing) return@post
                val html = b.html
                if (html.isEmpty()) {
                    b.event("paywall_load_failed", "failure", mapOf("stage" to Json.Str("config"), "error" to Json.Str("no_html"), "fallback_used" to Json.Bool(false)))
                    close("declined", null, "webview_failed")
                    return@post
                }
                val w = WebView(this)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                w.settings.mediaPlaybackRequiresUserGesture = false
                w.addJavascriptInterface(Bridge(), "FicturaAndroid")
                val script = b.injectedScript(binder)
                w.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (request.isForMainFrame && PaywallBridge.linkOk(url)) openUrl(url, false)
                        return true // the design itself never navigates away
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        b.event("paywall_webview_crashed", "failure")
                        close("declined", null, "webview_failed")
                        return true
                    }
                    override fun onPageFinished(view: WebView, url: String?) { root.removeView(spinner) }
                }
                // The bridge goes in before the design's own scripts.
                val withBridge = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let {
                    html.substring(0, it.range.last + 1) + "<script>$script</script>" + html.substring(it.range.last + 1)
                } ?: "<script>$script</script>$html"
                root.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                web = w
                w.loadDataWithBaseURL(Fictura.apiBase(), withBridge, "text/html", "utf-8", null)
            }
        }
    }

    override fun evaluate(js: String) { main.post { web?.evaluateJavascript(js, null) } }

    override fun close(result: String, productId: String?, reason: String) {
        main.post {
            if (closed) return@post
            closed = true
            bridge?.request?.onClose?.invoke(result, productId, reason)
            finish()
        }
    }

    override fun openUrl(url: String, inApp: Boolean) {
        main.post { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { } }
    }

    override fun haptic(kind: String) { main.post { web?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }

    override fun alert(message: String) {
        main.post { if (!isFinishing) AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show() }
    }

    override fun purchase(pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit) {
        val c = Fictura.purchaseController
        if (c == null) { done(PurchaseResult.Failed("no_purchase_controller")); return }
        try { c.purchase(this, pkg, context) { r -> main.post { done(r) } } } catch (e: Exception) { done(PurchaseResult.Failed(e.message ?: "error")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        bridge?.logDismissed("manual", "declined")
        close("declined", null, "manual")
    }

    override fun onDestroy() {
        bridge?.request?.id?.let { open.remove(it) }
        if (!closed) {
            closed = true
            bridge?.logDismissed("system", "declined")
            bridge?.request?.onClose?.invoke("declined", null, "system")
        }
        web?.destroy()
        super.onDestroy()
    }
}
