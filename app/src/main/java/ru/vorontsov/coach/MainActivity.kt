package ru.vorontsov.coach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ru.vorontsov.coach.ui.*

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf("today")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); enableEdgeToEdge()
        intent?.getStringExtra("tab")?.takeIf { it.isNotBlank() }?.let { tab = it }
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF81C784)) else lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF00796B))) {
                Scaffold(bottomBar = {
                    NavigationBar {
                        listOf(Triple("today", "Сегодня", Icons.Default.Today), Triple("goals", "Цели", Icons.Default.Flag),
                            Triple("coach", "Коуч", Icons.Default.Forum), Triple("review", "Итоги", Icons.Default.Insights),
                            Triple("settings", "Настройки", Icons.Default.Settings)).forEach { (k, l, i) ->
                            NavigationBarItem(tab == k, { tab = k }, { Icon(i, null) }, label = { Text(l, maxLines = 1) })
                        }
                    }
                }) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
                        when (tab) {
                            "today" -> TodayScreen { tab = it }; "goals" -> GoalsScreen(); "coach" -> CoachScreen()
                            "review" -> ReviewScreen(); else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); i.getStringExtra("tab")?.takeIf { it.isNotBlank() }?.let { tab = it } }
}
