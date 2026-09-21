package com.search.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Minimal viewer for the bundled legal pages (Privacy / Terms).
 * A plain WebView in its own activity so the back stack stays natural:
 * MainActivity -> Settings -> LegalActivity, and back walks straight home.
 */
class LegalActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: "file:///android_asset/privacy.html"
        // Where to go if [url] cannot be reached. Set when the page being
        // opened lives on the web and a copy of it ships inside the APK.
        val fallback = intent.getStringExtra("fallback")

        val container = FrameLayout(this)
        val web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.webViewClient = object : android.webkit.WebViewClient() {
            // One attempt only. Without this a fallback that also fails - an
            // asset renamed, say - would re-enter this and loop.
            private var fellBack = false

            private fun rescue(view: WebView, isMainFrame: Boolean) {
                if (!isMainFrame || fellBack || fallback == null) return
                fellBack = true
                view.loadUrl(fallback)
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) = rescue(view, request.isForMainFrame)

            // A reachable server answering 404 or 500 is not an onReceivedError
            // - the request succeeded, the answer was just not the document.
            // Without this the reader gets the host's error page as the policy.
            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                response: android.webkit.WebResourceResponse
            ) = rescue(view, request.isForMainFrame)

            /**
             * With no WebViewClient at all, a WebView hands every navigation
             * the page starts to the system - which is the only reason the
             * mailto: link in these documents has ever worked. Now that a
             * client exists the WebView would try to load mailto: itself and
             * the contact address would silently do nothing, so non-web
             * schemes are handed out explicitly.
             *
             * http and https stay here rather than being thrown to Chrome:
             * being ejected into another browser halfway through a privacy
             * policy is its own small insult.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val target = request.url
                val scheme = target.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "file") {
                    return false
                }
                try {
                    startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                    )
                } catch (e: Exception) {
                    // No mail app, no app for whatever this was. Swallowed
                    // rather than crashing a legal page.
                }
                return true
            }
        }
        web.loadUrl(url)
        container.addView(web)
        setContentView(container)

        // Edge-to-edge: pad by system-bar insets so content clears status/nav bars.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
