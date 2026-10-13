package ru.vorontsov.coach

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import ru.vorontsov.coach.ai.WebChat
import ru.vorontsov.coach.data.Provider

/** Однократный вход в веб-чат ИИ (Grok, DeepSeek, GigaChat, Qwen). Сессия сохраняется в cookies WebView. */
class WebLoginActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Provider.valueOf(intent.getStringExtra("p") ?: "GROK")
        val w = WebView(this); WebChat.configure(w); w.webViewClient = WebViewClient(); w.loadUrl(WebChat.url(p)!!)
        val done = Button(this).apply { text = "Готово — я вошёл в ${p.title}"; setOnClickListener { CookieManager.getInstance().flush()
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("web_${p.name}", true).apply(); finish() } }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(done); addView(w, LinearLayout.LayoutParams(-1, 0, 1f)) })
    }
}
