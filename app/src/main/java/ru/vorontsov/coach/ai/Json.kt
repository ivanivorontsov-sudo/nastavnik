package ru.vorontsov.coach.ai

import kotlinx.serialization.json.*

/** Терпимый разбор JSON из ответа ИИ: убирает ```-обёртки, текст вокруг, висячие запятые, «умные» кавычки. */
object LenientJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extractObject(raw: String): JsonObject? {
        val t = raw.replace("\u201C", "\"").replace("\u201D", "\"").replace("\u00AB", "\"").replace("\u00BB", "\"")
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(t).map { it.groupValues[1] }.toList().reversed()
        val candidates = fenced + listOf(t)
        for (c in candidates) {
            var start = c.indexOf('{')
            while (start >= 0) {
                val end = balancedEnd(c, start)
                if (end > start) {
                    val s = c.substring(start, end + 1).replace(Regex(",\\s*([}\\]])"), "$1")
                    runCatching { return json.parseToJsonElement(s).jsonObject }
                }
                start = c.indexOf('{', start + 1)
            }
        }
        return null
    }

    private fun balancedEnd(s: String, from: Int): Int {
        var depth = 0; var inStr = false; var esc = false
        for (i in from until s.length) {
            val ch = s[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
        }
        return -1
    }
    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }
    fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) { "true", "да", "yes" -> true; "false", "нет", "no" -> false; else -> null } }
}

/** Результат проверки фото — строгая валидация схемы {done, confidence, reason}. */
data class Verdict(val done: Boolean, val confidence: Double, val reason: String) {
    companion object {
        fun parse(raw: String): Verdict? {
            val o = LenientJson.extractObject(raw) ?: return null
            with(LenientJson) {
                val d = o.bool("done") ?: return null
                var c = o.num("confidence") ?: return null
                if (c > 1.0 && c <= 100.0) c /= 100.0
                if (c < 0.0 || c > 1.0) return null
                return Verdict(d, c, o.str("reason").take(400))
            }
        }
    }
}
