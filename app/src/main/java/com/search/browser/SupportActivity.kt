package com.search.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hosts the "Buy me a coffee" supporter page and owns the Billing flow.
 * Kept separate from LegalActivity so billing concerns don't leak into the
 * generic privacy/terms viewer.
 */
class SupportActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var billing: BillingManager

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(SupportBridge(), "SearchApp")
        // This WebView carries buySupport, so nothing but the page it was made
        // for may load in it: a remote page here would be a remote page holding
        // the billing bridge. supporter.html has no outbound links today, so
        // nothing can currently trigger it - this is the door being shut before
        // someone adds one.
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.toString().startsWith("file:///android_asset/supporter.html")) {
                    return false
                }
                try {
                    startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { /* nothing handles it; stay put */ }
                return true
            }
        }
        web.loadUrl("file:///android_asset/supporter.html")
        container.addView(web)
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        billing = BillingManager(
            activity = this,
            onPrices = { prices -> pushPrices(prices) },
            onSupporterChanged = { isSup -> pushSupporter(isSup) }
        )
        billing.start()
    }

    private fun pushPrices(prices: Map<String, String>) {
        prices.forEach { (id, formatted) ->
            val js = "window.__setPrice && window.__setPrice('" + id + "'," +
                org.json.JSONObject.quote(formatted) + ");"
            web.evaluateJavascript(js, null)
        }
    }

    private fun pushSupporter(isSupporter: Boolean) {
        runOnUiThread {
            web.evaluateJavascript("window.__setSupporter && window.__setSupporter($isSupporter);", null)
        }
    }

    inner class SupportBridge {
        @JavascriptInterface
        fun buySupport(productId: String) {
            runOnUiThread { billing.launch(productId) }
        }
        @JavascriptInterface
        fun supportInit() {
            runOnUiThread { pushSupporter(billing.isSupporter(this@SupportActivity)) }
        }
    }

    override fun onDestroy() {
        billing.end()
        super.onDestroy()
    }
}
