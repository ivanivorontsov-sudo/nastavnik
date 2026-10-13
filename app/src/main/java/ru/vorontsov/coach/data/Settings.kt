package ru.vorontsov.coach.data

import android.content.Context

enum class Provider(val title: String, val defBase: String, val defModel: String, val defVision: String) {
    DEMO("Демо-режим (без ключа, локальная имитация)", "", "", ""),
    GIGACHAT("GigaChat (Сбер)", "https://gigachat.devices.sberbank.ru/api/v1", "GigaChat-Max", "GigaChat-Max"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", ""),
    QWEN("Qwen (Alibaba DashScope)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-vl-max"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", "gemini-2.0-flash"),
    GROK("Grok (xAI)", "https://api.x.ai/v1", "grok-3-mini", "grok-2-vision-1212"),
    OPENAI("OpenAI-совместимый (любой)", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o-mini");
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var chatProvider: Provider get() = Provider.valueOf(p.getString("chatP", "DEMO")!!) ; set(v) = p.edit().putString("chatP", v.name).apply()
    var visionProvider: Provider get() = Provider.valueOf(p.getString("visP", "DEMO")!!) ; set(v) = p.edit().putString("visP", v.name).apply()
    fun key(pr: Provider) = p.getString("key_${pr.name}", "")!!
    fun setKey(pr: Provider, v: String) = p.edit().putString("key_${pr.name}", v.trim()).apply()
    fun base(pr: Provider) = p.getString("base_${pr.name}", pr.defBase)!!.ifBlank { pr.defBase }
    fun setBase(pr: Provider, v: String) = p.edit().putString("base_${pr.name}", v.trim()).apply()
    fun model(pr: Provider) = p.getString("model_${pr.name}", pr.defModel)!!.ifBlank { pr.defModel }
    fun setModel(pr: Provider, v: String) = p.edit().putString("model_${pr.name}", v.trim()).apply()
    fun visionModel(pr: Provider) = p.getString("vmodel_${pr.name}", pr.defVision)!!.ifBlank { pr.defVision }
    fun setVisionModel(pr: Provider, v: String) = p.edit().putString("vmodel_${pr.name}", v.trim()).apply()
    var threshold: Float get() = p.getFloat("thr", 0.75f); set(v) = p.edit().putFloat("thr", v).apply()
    var coachStyle: String get() = p.getString("style", "Поддерживающий")!!; set(v) = p.edit().putString("style", v).apply()
    var name: String get() = p.getString("name", "")!!; set(v) = p.edit().putString("name", v).apply()
    var checkinHour: Int get() = p.getInt("chk", 21); set(v) = p.edit().putInt("chk", v).apply()
    var grokHarsh: Boolean get() = p.getBoolean("harsh", false); set(v) = p.edit().putBoolean("harsh", v).apply()
    fun webLogged(pr: Provider) = p.getBoolean("web_${pr.name}", false)
    var onboarded: Boolean get() = p.getBoolean("onb", false); set(v) = p.edit().putBoolean("onb", v).apply()
}
