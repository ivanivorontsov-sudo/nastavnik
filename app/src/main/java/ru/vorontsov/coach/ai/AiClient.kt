package ru.vorontsov.coach.ai

import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.json.*
import ru.vorontsov.coach.data.Provider
import ru.vorontsov.coach.data.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AiException(msg: String, val retryable: Boolean) : Exception(msg)

data class ChatMsg(val role: String, val content: String)

/** Единый клиент: OpenAI-совместимые (DeepSeek, Qwen, OpenAI, GigaChat) + Gemini + демо. Вызывается только из фоновых Worker'ов. */
class AiClient(private val s: Settings, private val ctx: android.content.Context? = null) {

    fun chat(system: String, msgs: List<ChatMsg>, jsonMode: Boolean = false): String {
        val p = s.chatProvider
        if (s.key(p).isBlank() && WebChat.supported(p) && s.webLogged(p) && ctx != null) {
            // Без ключа — через веб-чат: весь контекст одним сообщением
            val prompt = "[ИНСТРУКЦИЯ]\n$system\n\n" + msgs.joinToString("\n") { (if (it.role == "user") "Пользователь: " else "Наставник: ") + it.content }
            return kotlinx.coroutines.runBlocking { WebChat.ask(ctx, p, prompt, null) }
        }
        if (system == Prompts.RANT_SYSTEM && s.key(p).isBlank()) return Demo.rant(msgs.last().content)
        return when (p) {
            Provider.DEMO -> Demo.chat(system, msgs)
            Provider.GEMINI -> gemini(p, s.model(p), system, msgs, null)
            else -> openAi(p, s.model(p), system, msgs, null, jsonMode)
        }
    }

    fun vision(system: String, user: String, photo: File): String {
        val p = s.visionProvider
        if (p != Provider.DEMO && s.key(p).isBlank() && WebChat.supported(p) && s.webLogged(p) && ctx != null)
            return kotlinx.coroutines.runBlocking { WebChat.ask(ctx, p, "[ИНСТРУКЦИЯ]\n$system\n\n$user", photo) }
        return when (p) {
            Provider.DEMO -> if (system == Prompts.VERIFY_SYSTEM) Demo.vision(photo) else Demo.chat(system, listOf(ChatMsg("user", user + "\n(к сообщению приложено фото)")))
            Provider.DEEPSEEK -> throw AiException("DeepSeek по API не принимает изображения — войдите в веб-чат DeepSeek или выберите другой ИИ", false)
            Provider.GEMINI -> gemini(p, s.visionModel(p), system, listOf(ChatMsg("user", user)), photo)
            else -> openAi(p, s.visionModel(p), system, listOf(ChatMsg("user", user)), photo, false)
        }
    }

    private fun b64(photo: File) = Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP)

    private fun openAi(p: Provider, model: String, system: String, msgs: List<ChatMsg>, photo: File?, jsonMode: Boolean): String {
        val key = s.key(p).ifBlank { throw AiException("Не задан ключ API для ${p.title}", false) }
        val token = if (p == Provider.GIGACHAT) GigaAuth.token(key) else key
        val gigaFile = if (p == Provider.GIGACHAT && photo != null) GigaAuth.upload(s.base(p), token, photo) else null
        val body = buildJsonObject {
            put("model", model); put("temperature", if (photo != null) 0.0 else 0.6)
            if (jsonMode && p != Provider.GIGACHAT) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                msgs.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", m.role)
                        val last = i == msgs.lastIndex
                        if (photo != null && last && p != Provider.GIGACHAT) putJsonArray("content") {
                            addJsonObject { put("type", "text"); put("text", m.content) }
                            addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:image/jpeg;base64,${b64(photo)}") } }
                        } else {
                            put("content", m.content)
                            if (gigaFile != null && last) putJsonArray("attachments") { add(gigaFile) }
                        }
                    }
                }
            }
        }
        val resp = post("${s.base(p).trimEnd('/')}/chat/completions", body.toString(), mapOf("Authorization" to "Bearer $token"))
        val o = LenientJson.json.parseToJsonElement(resp).jsonObject
        return o["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException("Пустой ответ ИИ", true)
    }

    private fun gemini(p: Provider, model: String, system: String, msgs: List<ChatMsg>, photo: File?): String {
        val key = s.key(p).ifBlank { throw AiException("Не задан ключ API для Gemini", false) }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                msgs.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", m.content) }
                            if (photo != null && i == msgs.lastIndex) addJsonObject { putJsonObject("inline_data") { put("mime_type", "image/jpeg"); put("data", b64(photo)) } }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") { put("temperature", if (photo != null) 0.0 else 0.6) }
        }
        val resp = post("${s.base(p).trimEnd('/')}/models/$model:generateContent?key=$key", body.toString(), emptyMap())
        val o = LenientJson.json.parseToJsonElement(resp).jsonObject
        return o["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }?.ifBlank { null }
            ?: throw AiException("Пустой ответ Gemini", true)
    }

    companion object {
        fun post(url: String, body: String, headers: Map<String, String>, contentType: String = "application/json"): String {
            val c = (URL(url).openConnection() as HttpURLConnection)
            try {
                c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 120_000; c.doOutput = true
                c.setRequestProperty("Content-Type", contentType); c.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw AiException("HTTP $code: ${txt.take(200)}", code == 429 || code >= 500)
                return txt
            } catch (e: java.io.IOException) { throw AiException("Сеть: ${e.message}", true) }
            finally { c.disconnect() }
        }
    }
}

/** GigaChat: OAuth-токен по ключу авторизации (живёт 30 мин) и загрузка файла для vision. */
object GigaAuth {
    private var tok: String? = null; private var exp = 0L
    @Synchronized fun token(authKey: String): String {
        if (tok != null && System.currentTimeMillis() < exp - 60_000) return tok!!
        val r = AiClient.post("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", "scope=GIGACHAT_API_PERS",
            mapOf("Authorization" to "Basic $authKey", "RqUID" to UUID.randomUUID().toString()), "application/x-www-form-urlencoded")
        val o = LenientJson.json.parseToJsonElement(r).jsonObject
        tok = o["access_token"]!!.jsonPrimitive.content; exp = o["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 1_500_000)
        return tok!!
    }
    fun upload(base: String, token: String, photo: File): String {
        val b = "----coach${System.nanoTime()}"
        val c = URL("${base.trimEnd('/')}/files").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { os ->
                os.write("--$b\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\ngeneral\r\n--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                os.write(photo.readBytes()); os.write("\r\n--$b--\r\n".toByteArray())
            }
            if (c.responseCode !in 200..299) throw AiException("GigaChat files HTTP ${c.responseCode}", c.responseCode >= 500)
            return LenientJson.json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject["id"]!!.jsonPrimitive.content
        } catch (e: java.io.IOException) { throw AiException("Сеть: ${e.message}", true) } finally { c.disconnect() }
    }
}

/** Демо-режим без ключей: правдоподобные ответы; проверка фото — по яркости/контрасту кадра (тёмный/пустой кадр отклоняется). */
object Demo {
    fun chat(system: String, msgs: List<ChatMsg>): String {
        Thread.sleep(1200)
        val last = msgs.lastOrNull()?.content ?: ""
        if (system == Prompts.PLAN_SYSTEM) {
            val goal = Regex("Цель: (.*)").find(last)?.groupValues?.get(1)?.take(60) ?: "цель"
            return """```json
{"smart":"$goal — измеримо, к сроку, по 20–30 минут в день","milestones":[{"week":1,"title":"Старт: настроить среду и ритуал"},{"week":2,"title":"Закрепить ежедневное действие"},{"week":4,"title":"Первый измеримый результат"},{"week":8,"title":"Удвоить нагрузку и подвести итоги"}],
"tasks":[{"title":"Сделать главный шаг дня по цели (20 мин)","proof":"рабочее место/результат шага на фото","time":"09:00","minutes":20},{"title":"Записать прогресс в тетрадь","proof":"страница тетради с сегодняшней записью","time":"21:00","minutes":5}],
"advice":"Начинаем с маленьких шагов — главное не пропускать два дня подряд. Привяжи действие к утреннему кофе: «после кофе — 20 минут по цели».",}
```"""
        }
        return when {
            last.contains("Вечерняя рефлексия") -> "Хороший день для честного взгляда на себя. Отметь одно, что получилось, и уменьши завтрашний шаг, если было тяжело. Что поможет завтра начать вовремя?"
            last.contains("[проверка]") -> "Как продвигается цель? Ты уже сделал сегодняшний шаг? Даже 2 минуты считаются — главное не прерывать серию 🔥"
            else -> "(демо-коуч) Понимаю. Давай по модели GROW: чего именно ты хочешь достичь к концу недели? Что мешает прямо сейчас и какой самый маленький шаг ты можешь сделать сегодня? Сформулируй: «Если ___, то я ___»."
        }
    }
    fun rant(facts: String): String {
        Thread.sleep(1000)
        val n = Regex("Пропущено звонков: (\\d+)").find(facts)?.groupValues?.get(1) ?: "несколько"
        val k = Regex("Откладываний: (\\d+)").find(facts)?.groupValues?.get(1) ?: "куча"
        return "(демо Грока) Так, слушай сюда, чёрт возьми. Пропущено звонков: $n, откладываний: $k. Это что за хрень? Ты сам поставил эту цель, никто тебя за язык не тянул. Отмазки свои засунь куда подальше — они ни хрена не работают. Каждое «потом» — это минус к тебе завтрашнему. Хватит жевать сопли. Встал, сделал задачу, сфоткал. Прямо сейчас, блин! Пошёл!"
    }
    fun vision(photo: File): String {
        Thread.sleep(1500)
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bm = BitmapFactory.decodeFile(photo.absolutePath, o) ?: return """{"done":false,"confidence":0.0,"reason":"Не удалось прочитать фото"}"""
        var sum = 0.0; var sq = 0.0; var n = 0
        for (x in 0 until bm.width step 2) for (y in 0 until bm.height step 2) {
            val c = bm.getPixel(x, y); val l = ((c shr 16 and 255) * 0.3 + (c shr 8 and 255) * 0.59 + (c and 255) * 0.11); sum += l; sq += l * l; n++
        }
        val mean = sum / n; val sd = Math.sqrt(sq / n - mean * mean)
        return if (mean > 40 && sd > 18) """{"done":true,"confidence":0.86,"reason":"(демо) На кадре различима сцена с деталями, похоже на выполненную задачу"}"""
        else """{"done":false,"confidence":0.2,"reason":"(демо) Кадр слишком тёмный или пустой — доказательства не видно"}"""
    }
}
