package ru.vorontsov.coach.ai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import ru.vorontsov.coach.data.Provider

/**
 * Работа с ИИ без API-ключа — через веб-чат сайта в скрытом WebView (вход один раз, сессия в cookies).
 * Подход как в capture.js из «ПрокурорПлюс»: вставить промпт, отправить, дождаться стабильного ответа, забрать текст.
 * Ограничения: зависит от вёрстки сайта, капчи/выход из аккаунта ломают сценарий; фото вкладывается через input[type=file]/вставку — может не сработать при смене вёрстки.
 */
object WebChat {
    fun url(p: Provider) = when (p) {
        Provider.GROK -> "https://grok.com/"; Provider.DEEPSEEK -> "https://chat.deepseek.com/"
        Provider.GIGACHAT -> "https://giga.chat/"; Provider.QWEN -> "https://chat.qwen.ai/"; else -> null
    }
    fun supported(p: Provider) = url(p) != null

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(w: WebView) {
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true; w.settings.databaseEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
    }

    /** JS: вложить фото в чат — через input[type=file] (DataTransfer) и, как запасной путь, через событие вставки (paste) в поле ввода. */
    private fun attachJs(b64: String) = """(async()=>{try{const bin=atob('$b64');const u=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)u[i]=bin.charCodeAt(i);
const f=new File([u],'proof.jpg',{type:'image/jpeg',lastModified:Date.now()});const mk=()=>{const d=new DataTransfer();d.items.add(f);return d};
const ins=[...document.querySelectorAll('input[type=file]')];let ok=0;
for(const i of ins){if(i.accept&&!/image|\*|jpg|jpeg/i.test(i.accept))continue;try{i.files=mk().files;i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));ok++;break}catch(e){}}
if(!ok){const ed=[...document.querySelectorAll('textarea,[contenteditable=true],div[role=textbox]')].pop();if(ed){ed.focus();const ev=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:mk()});ed.dispatchEvent(ev);ok=2}}
window.__ppAttach=ok?'ok'+ok:'none'}catch(e){window.__ppAttach='err:'+e}})();'ran'"""

    private fun photoB64(photo: java.io.File): String {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(photo.absolutePath, o)
        var ss = 1; while (maxOf(o.outWidth, o.outHeight) / ss > 1280) ss *= 2
        val bm = android.graphics.BitmapFactory.decodeFile(photo.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
        val out = java.io.ByteArrayOutputStream(); bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    suspend fun ask(ctx: Context, p: Provider, prompt: String, photo: java.io.File? = null): String = withTimeout(if (photo != null) 240_000 else 180_000) {
        val b64 = photo?.let { photoB64(it) }
        val js = ctx.assets.open("capture.js").bufferedReader().readText()
        val result = CompletableDeferred<String>()
        var web: WebView? = null
        val h = Handler(Looper.getMainLooper())
        withContext(Dispatchers.Main) {
            val w = WebView(ctx.applicationContext); web = w; configure(w)
            w.measure(1080, 1920); w.layout(0, 0, 1080, 1920) // «виртуальный» размер, чтобы элементы считались видимыми
            var started = false
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    if (started) return; started = true
                    h.postDelayed({
                        v.evaluateJavascript(js) {
                            val q = org.json.JSONObject.quote(prompt)
                            v.evaluateJavascript("window.__pp.mark($q)") { mk ->
                                if (mk?.contains("\\\"editor\\\":false") == true) { result.completeExceptionally(AiException("Веб-чат ${p.title}: не найдено поле ввода — войдите в аккаунт в Настройках", false)); return@evaluateJavascript }
                                val doSend = { v.evaluateJavascript("window.__pp.send($q)", null) }
                                if (b64 == null) doSend() else v.evaluateJavascript(attachJs(b64)) {
                                    // ждём загрузку фото на сайт, проверяем, что вложение принято
                                    h.postDelayed({ v.evaluateJavascript("window.__ppAttach") { a ->
                                        if (a?.contains("ok") != true) result.completeExceptionally(AiException("Веб-чат ${p.title}: не удалось вложить фото ($a)", false)) else doSend()
                                    } }, 7000)
                                }
                                var last = ""; var stable = 0
                                val poll = object : Runnable { override fun run() {
                                    if (result.isCompleted) return
                                    v.evaluateJavascript("window.__pp.poll()") { r ->
                                        runCatching {
                                            val o = org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String)
                                            val t = o.optString("text"); val busy = o.optBoolean("busy")
                                            if (t.isNotBlank() && !busy && t == last) stable++ else stable = 0
                                            last = t
                                            if (stable >= 2) result.complete(t)
                                        }
                                        if (!result.isCompleted) h.postDelayed(this, 2000)
                                    }
                                } }
                                h.postDelayed(poll, if (b64 != null) 14000 else 4000)
                            }
                        }
                    }, 4000)
                }
            }
            w.loadUrl(url(p)!!)
        }
        try { result.await() } finally { withContext(NonCancellable + Dispatchers.Main) { CookieManager.getInstance().flush(); web?.destroy() } }
    }
}
