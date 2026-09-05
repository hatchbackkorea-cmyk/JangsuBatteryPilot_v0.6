package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Read-only participant view of the same field RACE broadcast page used on the big screen. */
class RaceBroadcastActivity : Activity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = RaceServerClient(this)
        val store = RaceDataStore(this)
        val eventCode = intent.getStringExtra(EXTRA_EVENT_CODE).orEmpty().trim().uppercase()
            .ifBlank { store.lastJoined()?.config?.eventCode.orEmpty() }
        val baseUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty().trim().trimEnd('/')
            .ifBlank { client.baseUrl().trim().trimEnd('/') }
        if (eventCode.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            Toast.makeText(this, "참가 중인 대회 또는 현장 서버를 확인해 주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 13))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(18, 25, 36))
        }
        top.addView(Button(this).apply {
            text = "‹ START 화면"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(132), dp(48)))
        top.addView(TextView(this).apply {
            text = "실시간 중계 · $eventCode"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(top)

        val web = WebView(this).apply {
            setBackgroundColor(Color.rgb(5, 8, 13))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // QR codes are useful on the venue monitor but only obscure information on a participant phone.
                    view?.evaluateJavascript(
                        "document.querySelectorAll('.qrBox').forEach(function(e){e.style.display='none'});void(0);",
                        null
                    )
                }
            }
        }
        webView = web
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val encoded = URLEncoder.encode(eventCode, "UTF-8")
        web.loadUrl("$baseUrl/race-live/$encoded?participant=1")
    }

    override fun onDestroy() {
        webView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        webView = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_EVENT_CODE = "event_code"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
