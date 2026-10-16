package ru.vorontsov.coach.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

object Disclaimer {
    const val TEXT = "«Наставник» помогает ставить цели и выполнять задачи. Приложение не является психологической или медицинской помощью и не заменяет консультацию специалиста (психолога, психотерапевта, врача). Советы ИИ могут быть неточными."
    const val CRISIS = "Если вам сейчас тяжело или есть угроза жизни, позвоните 112 или 8-800-2000-122 (бесплатный телефон доверия)."
    const val PRIVACY_URL = "https://ivanivorontsov-sudo.github.io/supersmart-privacy/nastavnik.html"
    private const val KEY = "disclaimer_v1"

    fun isAccepted(ctx: Context) = ctx.getSharedPreferences("disclaimer", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun accept(ctx: Context) = ctx.getSharedPreferences("disclaimer", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private fun open(ctx: Context, uri: String, action: String = Intent.ACTION_VIEW) {
    runCatching { ctx.startActivity(Intent(action, Uri.parse(uri))) }
}

@Composable fun FirstRunDisclaimer() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(!Disclaimer.isAccepted(ctx)) }
    if (show) AlertDialog(onDismissRequest = {},
        confirmButton = { TextButton({ Disclaimer.accept(ctx); show = false }) { Text("Понятно") } },
        dismissButton = { TextButton({ open(ctx, Disclaimer.PRIVACY_URL) }) { Text("Конфиденциальность") } },
        title = { Text("Важно") }, text = { Text(Disclaimer.TEXT + "\n\n" + Disclaimer.CRISIS) })
}

@Composable fun AboutScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    var lic by remember { mutableStateOf<Pair<String, String>?>(null) }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val cur = lic
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (cur != null) {
                    BackHandler { lic = null }
                    val text = remember(cur) { runCatching { ctx.assets.open("licenses/${cur.second}").bufferedReader().use { it.readText() } }.getOrDefault("") }
                    TextButton({ lic = null }) { Text("← Назад") }
                    Text(cur.first, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    TextButton(close) { Text("← Назад") }
                    Text("О приложении", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(Disclaimer.TEXT, Modifier.padding(top = 12.dp))
                    Text(Disclaimer.CRISIS, Modifier.padding(top = 8.dp))
                    Button({ open(ctx, "tel:112", Intent.ACTION_DIAL) }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Позвонить 112") }
                    OutlinedButton({ open(ctx, "tel:88002000122", Intent.ACTION_DIAL) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Телефон доверия 8-800-2000-122") }
                    Text("Конфиденциальность", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                    Text("Фото с камеры и тексты отправляются только выбранному вами сервису ИИ. У разработчика нет сервера для этого приложения.", fontSize = 13.sp)
                    TextButton({ open(ctx, Disclaimer.PRIVACY_URL) }) { Text("Политика конфиденциальности") }
                    Text("Сторонние компоненты и лицензии", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    listOf("Сторонние компоненты" to "THIRD_PARTY_NOTICES.md", "Apache License 2.0" to "Apache-2.0.txt").forEach { item ->
                        TextButton({ lic = item }) { Text(item.first) }
                    }
                }
            }
        }
    }
}
