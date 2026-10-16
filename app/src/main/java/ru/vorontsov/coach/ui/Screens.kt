package ru.vorontsov.coach.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AS
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vorontsov.coach.core.*
import ru.vorontsov.coach.data.*
import ru.vorontsov.coach.ai.WebChat
import java.text.SimpleDateFormat
import java.util.*

private val dao get() = App.i.db.dao()

@Composable fun Title(t: String, sub: String? = null) {
    Column(Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) {
        Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- Сегодня ---------------- */
@Composable fun TodayScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val tasks by dao.tasks().collectAsState(emptyList())
    val comps by dao.completions().collectAsState(emptyList())
    val missed by dao.missed().collectAsState(emptyList())
    val goals by dao.goals().collectAsState(emptyList())
    val td = today(); val doneIds = comps.filter { it.day == td }.map { it.taskId }.toSet()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Title("Сегодня", SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())) }
        item { PermissionsCard() }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("🔥 Серия", "${Stats.streak(comps)} дн.", Modifier.weight(1f))
                StatTile("✅ Сегодня", "${doneIds.size}/${tasks.count { it.enabled || it.id in doneIds }}", Modifier.weight(1f))
                StatTile("🎯 Цели", "${goals.count { it.status == "active" }}", Modifier.weight(1f))
            }
        }
        if (tasks.isEmpty()) item {
            Card(Modifier.padding(16.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Пока нет задач", fontWeight = FontWeight.Bold)
                Text("Поставьте цель — наставник разложит её на вехи и ежедневные шаги и будет звонить в нужное время.")
                Button({ go("goals") }, Modifier.padding(top = 8.dp)) { Text("Поставить цель") }
            } }
        }
        items(tasks, key = { it.id }) { t ->
            val done = t.id in doneIds
            ListItem(
                modifier = Modifier.clickable(enabled = t.state != "idle") { ctx.startActivity(Intent(ctx, ru.vorontsov.coach.CallActivity::class.java).putExtra("id", t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                headlineContent = { Text(t.title, fontWeight = FontWeight.Medium) },
                supportingContent = { Text(buildString {
                    append("%02d:%02d · %d мин".format(t.hour, t.minute, t.durationMin)); if (t.state == "active") append(" · ⏱ идёт таймер (нажмите)"); if (t.proof.isNotBlank()) append(" · 📷 ${t.proof}")
                    if (t.state == "verifying") append(" · ⏳ проверка фото") else if (t.snoozes > 0) append(" · отложено ${t.snoozes}/${t.maxSnoozes}")
                    if (!done && t.nextAt > 0) append(" · звонок ${SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(t.nextAt))}")
                }) },
                leadingContent = { Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (done) Color(0xFF43A047) else MaterialTheme.colorScheme.outline) },
                trailingContent = { IconButton({ CallService.ring(ctx, t.id, "", force = true) }) { Icon(Icons.Default.Call, "Позвонить сейчас") } }
            )
        }
        item {
            OutlinedButton({ scope.launch(Dispatchers.IO) {
                val t = dao.allTasks().firstOrNull() ?: return@launch
                val n = t.copy(nextAt = System.currentTimeMillis() + 10_000, enabled = true); dao.upsertTask(n); Scheduler.set(ctx, n)
            } }, Modifier.padding(16.dp)) { Icon(Icons.Default.Alarm, null); Spacer(Modifier.width(8.dp)); Text("Тестовый звонок через 10 секунд") }
        }
        if (missed.isNotEmpty()) {
            item { Text("Пропущенные звонки", Modifier.padding(16.dp, 8.dp), fontWeight = FontWeight.Bold) }
            items(missed.take(5)) { m -> ListItem({ Text(m.title) }, supportingContent = { Text(SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(m.at))) },
                leadingContent = { Icon(Icons.Default.PhoneMissed, null, tint = Color(0xFFE53935)) }) }
        }
    }
}

@Composable fun StatTile(l: String, v: String, m: Modifier) = Card(m) { Column(Modifier.padding(12.dp)) {
    Text(l, fontSize = 13.sp); Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold) } }

@Composable fun PermissionsCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val back = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    key(tick) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val items = buildList {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) add("Уведомления" to { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else back.launch(Intent(AS.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AS.EXTRA_APP_PACKAGE, ctx.packageName)) })
            if (!Scheduler.canExact(ctx)) add("Точные будильники" to { back.launch(Intent(AS.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) })
            if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) add("Полноэкранные звонки" to { back.launch(Intent(AS.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) })
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) add("Камера (фото-доказательства)" to { cam.launch(Manifest.permission.CAMERA) })
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) add("Без ограничений батареи" to {
                @Suppress("BatteryLife") back.launch(Intent(AS.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) })
        }
        if (items.isNotEmpty()) Card(Modifier.padding(16.dp, 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Чтобы звонки наставника работали надёжно, разрешите:", fontWeight = FontWeight.Bold)
                items.forEach { (n, a) -> Row(verticalAlignment = Alignment.CenterVertically) { Text("• $n", Modifier.weight(1f)); TextButton(a) { Text("Разрешить") } } }
            }
        }
    }
}

/* ---------------- Цели + GROW-диалог ---------------- */
private data class Step(val key: String, val q: String, val hint: String)
private val GROW = listOf(
    Step("title", "G — Цель. Чего вы хотите достичь? Сформулируйте конкретно.", "Например: пробежать 10 км без остановки"),
    Step("why", "G — Зачем? Почему это важно лично для вас?", "Желаемый результат (WOOP: Wish + Outcome)"),
    Step("metric", "S.M.A.R.T. — Как вы измерите успех и к какому сроку?", "Например: 10 км за 60 минут к 1 декабря"),
    Step("reality", "R — Реальность. Где вы сейчас? Что уже пробовали?", "Например: сейчас бегаю 3 км раз в неделю"),
    Step("options", "O — Варианты. Какие пути к цели вы видите?", "Приложение, тренер, клуб, утро/вечер…"),
    Step("obstacle", "WOOP — Главное внутреннее препятствие. Что вам обычно мешает?", "Например: лень вечером после работы"),
    Step("ifThen", "Намерение «если — то». Что вы сделаете, когда появится препятствие?", "Если устал после работы, то выйду хотя бы на 10 минут"),
    Step("will", "W — Обязательство. Когда и к какой привычке вы привяжете ежедневный шаг?", "После утреннего кофе — 20 минут (habit stacking)"),
)

@Composable fun GoalsScreen() {
    val goals by dao.goals().collectAsState(emptyList())
    var wizard by remember { mutableStateOf(false) }
    if (wizard) { GoalWizard { wizard = false }; return }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Title("Цели", "Методики: GROW · SMART · WOOP · «если — то» · привязка привычек") }
            if (goals.isEmpty()) item { Text("Нажмите «Новая цель» — наставник проведёт вас через короткий коуч-диалог.", Modifier.padding(16.dp)) }
            items(goals, key = { it.id }) { g -> GoalCard(g) }
        }
        ExtendedFloatingActionButton(onClick = { wizard = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новая цель") })
    }
}

@Composable fun GoalCard(g: Goal) {
    val ms by dao.milestones(g.id).collectAsState(emptyList())
    val comps by dao.completions().collectAsState(emptyList())
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    LaunchedEffect(g.id, ms.size) { tasks = withContext(Dispatchers.IO) { dao.tasksOf(g.id) } }
    Card(Modifier.padding(16.dp, 6.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            AssistChip({}, { Text(if (g.status == "planning") "план готовится…" else "активна") })
        }
        if (g.metric.isNotBlank()) Text("SMART: ${g.metric}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (g.ifThen.isNotBlank()) Text("Если-то: ${g.ifThen}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        val n = comps.count { it.goalId == g.id }
        LinearProgressIndicator({ (ms.count { it.done }.toFloat() / ms.size.coerceAtLeast(1)) }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text("Подтверждено фото: $n · вех: ${ms.count { it.done }}/${ms.size}", fontSize = 13.sp)
        ms.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(m.done, { v -> scope.launch(Dispatchers.IO) { dao.upsertMilestone(m.copy(done = v)) } })
            Text("Нед. ${m.week}: ${m.title}") } }
        if (tasks.isNotEmpty()) Text("Ежедневные звонки:", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        tasks.forEach { t -> Text("📞 %02d:%02d — ${t.title}".format(t.hour, t.minute), fontSize = 14.sp) }
        if (g.status == "planning") Text("⏳ ИИ составляет план в фоне — можно закрыть приложение, придёт уведомление.", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Row { TextButton({ Workers.enqueue(ctx, "plan", g.id) }) { Text("Перепланировать") }
            TextButton({ scope.launch(Dispatchers.IO) { dao.tasksOf(g.id).forEach { Scheduler.cancel(ctx, it.id); dao.delTask(it.id) }; dao.delGoal(g.id) } }) { Text("Удалить") } }
    } }
}

@Composable fun GoalWizard(close: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val ans = remember { mutableStateMapOf<String, String>() }
    var input by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    val cam = rememberLauncherForActivityResult(ru.vorontsov.coach.CameraCaptureActivity.Contract()) { if (it != null) photo = it }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.Default.Close, null) }
            Text("Коуч-сессия: новая цель", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ listOf("Пробежать 10 км без остановки", "Хочу больше энергии и уверенности в себе", "10 км за 60 минут к 1 декабря",
                "Бегаю 3 км раз в неделю", "Бег по утрам, беговой клуб по выходным", "Лень вечером после работы",
                "Если устал, то выйду хотя бы на 10 минут", "После утреннего кофе — пробежка").forEachIndexed { i, v -> ans[GROW[i].key] = v }
                step = GROW.lastIndex; input = ans[GROW[step].key] ?: "" }) { Text("Пример") }
        }
        LinearProgressIndicator({ (step + 1f) / GROW.size }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        val wls = rememberLazyListState()
        val imeB = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
        LaunchedEffect(step, imeB) { wls.animateScrollToItem(step * 2) }
        LazyColumn(Modifier.weight(1f), state = wls) {
            items(step) { i -> Bubble(GROW[i].q, false); Bubble(ans[GROW[i].key] ?: "", true) }
            item { Bubble(GROW[step].q, false) }
        }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), placeholder = { Text(GROW[step].hint) }, minLines = 2, maxLines = 5)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ cam.launch("Сфотографируйте текущую ситуацию (необязательно)") }) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text(if (photo == null) "Фото ситуации" else "Фото приложено ✓") }
            if (photo != null) TextButton({ photo = null }) { Text("Убрать") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            if (step > 0) TextButton({ step--; input = ans[GROW[step].key] ?: "" }) { Text("Назад") }
            Button({
                ans[GROW[step].key] = input.trim()
                if (step < GROW.lastIndex) { step++; input = ans[GROW[step].key] ?: "" }
                else scope.launch {
                    val id = withContext(Dispatchers.IO) { dao.upsertGoal(Goal(title = ans["title"].orEmpty().ifBlank { "Моя цель" }, why = ans["why"].orEmpty(), metric = ans["metric"].orEmpty(),
                        reality = ans["reality"].orEmpty(), options = ans["options"].orEmpty(), obstacle = ans["obstacle"].orEmpty(), ifThen = ans["ifThen"].orEmpty(), will = ans["will"].orEmpty(), photo = photo.orEmpty(), status = "planning")) }
                    Workers.enqueue(ctx, "plan", id); close()
                }
            }, enabled = input.isNotBlank()) { Text(if (step < GROW.lastIndex) "Далее" else "Составить план с ИИ") }
        }
    }
}

@Composable fun Bubble(t: String, me: Boolean, pending: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pending) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (pending) "Наставник печатает… (запрос в фоне)" else t) }
        }
    }
}

/* ---------------- Коуч-чат ---------------- */
@Composable fun CoachScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val msgs by dao.msgs().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    val cam = rememberLauncherForActivityResult(ru.vorontsov.coach.CameraCaptureActivity.Contract()) { if (it != null) photo = it }
    val ls = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(msgs.size, imeBottom) { if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Title("Коуч", "Помнит ваши цели, серии и рефлексии · стиль: ${App.i.settings.coachStyle}")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = ls) {
            if (msgs.isEmpty()) item { Bubble("Привет! Я ваш наставник. Расскажите, что сейчас важнее всего — или спросите, как не сорваться сегодня.", false) }
            items(msgs, key = { it.id }) { m -> Bubble((if (m.photo.isNotBlank()) "📷 " else "") + m.text, m.role == "user", m.pending) }
        }
        if (photo != null) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📷 Фото приложено", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton({ photo = null }) { Text("Убрать") } }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ cam.launch("Фото для наставника") }) { Icon(Icons.Default.PhotoCamera, "Камера") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Сообщение наставнику") }, maxLines = 4)
            IconButton({
                val t = input.trim().ifEmpty { if (photo != null) "Посмотри на фото" else "" }; if (t.isEmpty()) return@IconButton; input = ""
                val ph = photo.orEmpty(); photo = null
                scope.launch(Dispatchers.IO) { dao.addMsg(Msg(role = "user", text = t, photo = ph)); val pid = dao.addMsg(Msg(role = "assistant", text = "", pending = true)); Workers.enqueue(ctx, "chat", pid) }
            }) { Icon(Icons.Default.Send, null) }
        }
    }
}

/* ---------------- Итоги: рефлексия + статистика ---------------- */
@Composable fun ReviewScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val comps by dao.completions().collectAsState(emptyList())
    val refl by dao.reflections().collectAsState(emptyList())
    var mood by remember { mutableIntStateOf(3) }; var text by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Title("Итоги", "Ежедневная рефлексия и прогресс") }
        item { Card(Modifier.padding(16.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Последние 7 дней", fontWeight = FontWeight.Bold)
            val d = Stats.last7(comps); val mx = (d.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                d.forEach { (day, n) -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$n", fontSize = 12.sp)
                    Box(Modifier.width(26.dp).height((100f * n / mx).dp.coerceAtLeast(3.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                    Text(day, fontSize = 12.sp) } }
            }
            Text("Серия: ${Stats.streak(comps)} дн. · всего подтверждено: ${comps.size} · средняя уверенность ИИ: ${if (comps.isEmpty()) "—" else "${(comps.map { it.confidence }.average() * 100).toInt()}%"}", fontSize = 13.sp)
        } } }
        item { Card(Modifier.padding(16.dp, 0.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Вечерняя рефлексия", fontWeight = FontWeight.Bold)
            Text("Настроение: ${listOf("😞", "😕", "😐", "🙂", "😄")[mood - 1]}")
            Slider(mood.toFloat(), { mood = it.toInt() }, valueRange = 1f..5f, steps = 3)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Что получилось? Что мешало? Чему научился?") }, minLines = 3)
            Button({ val t = text.trim(); text = ""
                scope.launch(Dispatchers.IO) { val id = dao.upsertReflection(Reflection(day = today(), mood = mood, text = t)); Workers.enqueue(ctx, "reflect", id) }
            }, Modifier.padding(top = 8.dp), enabled = text.isNotBlank()) { Text("Сохранить и получить отзыв коуча") }
        } } }
        items(refl, key = { it.id }) { r -> Card(Modifier.padding(16.dp, 6.dp).fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${r.day} · настроение ${r.mood}/5", fontWeight = FontWeight.Medium); Text(r.text)
            Text(if (r.aiFeedback.isBlank()) "⏳ коуч готовит отзыв (в фоне)…" else "🧭 ${r.aiFeedback}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        } } }
    }
}

/* ---------------- Настройки ---------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen() {
    val s = App.i.settings; val ctx = LocalContext.current
    var chatP by remember { mutableStateOf(s.chatProvider) }; var visP by remember { mutableStateOf(s.visionProvider) }
    var thr by remember { mutableFloatStateOf(s.threshold) }; var style by remember { mutableStateOf(s.coachStyle) }; var name by remember { mutableStateOf(s.name) }
    var chk by remember { mutableFloatStateOf(s.checkinHour.toFloat()) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(name, { name = it; s.name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Как к вам обращаться") }) }
        item { Text("Стиль наставника", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("Поддерживающий", "Строгий тренер", "Дружеский", "Деловой").forEach { v ->
                FilterChip(style == v, { style = v; s.coachStyle = v }, { Text(v) }, Modifier.padding(end = 6.dp)) } } }
        item { Text("Проактивный чекин коуча: ${chk.toInt()}:00", Modifier.padding(top = 12.dp))
            Slider(chk, { chk = it }, valueRange = 6f..23f, steps = 16, onValueChangeFinished = { s.checkinHour = chk.toInt(); Workers.scheduleCheckins(ctx) }) }
        item { ProviderPicker("ИИ для чата и планов", chatP, Provider.entries) { chatP = it; s.chatProvider = it } }
        item { ProviderPicker("ИИ для проверки фото (vision)", visP, Provider.entries) { visP = it; s.visionProvider = it } }
        item { var h by remember { mutableStateOf(s.grokHarsh) }; var warn by remember { mutableStateOf(false) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Жёсткий режим Грока (18+)", fontWeight = FontWeight.Medium)
                    Text("Только с Grok. При пропусках на экране звонка появится кнопка «Прослушать Грока» — грубая речь с матом, звучит только по нажатию.", fontSize = 12.sp) }
                Switch(h, { v -> if (v) warn = true else { h = false; s.grokHarsh = false } })
            }
            if (warn) AlertDialog({ warn = false }, confirmButton = { TextButton({ h = true; s.grokHarsh = true; warn = false }) { Text("Мне есть 18, включить") } },
                dismissButton = { TextButton({ warn = false }) { Text("Отмена") } }, title = { Text("18+ · ненормативная лексика") },
                text = { Text("Грок будет ругать вас матом за пропуски и откладывания. Без оскорблений личности и угроз. Речь воспроизводится только по кнопке.") })
        }
        item { Text("Порог уверенности для зачёта фото: ${(thr * 100).toInt()}%", Modifier.padding(top = 12.dp))
            Slider(thr, { thr = it }, valueRange = 0.6f..0.95f, onValueChangeFinished = { s.threshold = thr }) }
        item { Text("Ключи API", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold) }
        items(Provider.entries.filter { it != Provider.DEMO }) { p -> KeyCard(p) }
        item { var about by remember { mutableStateOf(false) }
            OutlinedButton({ about = true }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("О приложении, конфиденциальность и лицензии") }
            if (about) AboutScreen { about = false } }
        item { Card(Modifier.padding(top = 12.dp)) { Text("Промпт проверки фото зашит в приложение и не редактируется — это защита от «уговоров» ИИ. Фото снимается только камерой, галерея недоступна. Все запросы к ИИ идут в фоне через WorkManager и дождутся сети. Приложение никогда не включает выключенный телефон: пропущенные за время выключения звонки показываются списком.", Modifier.padding(12.dp), fontSize = 13.sp) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ProviderPicker(label: String, cur: Provider, opts: List<Provider>, set: (Provider) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, Modifier.padding(top = 12.dp)) {
        OutlinedTextField(cur.title, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) { opts.forEach { p -> DropdownMenuItem({ Text(p.title) }, { set(p); open = false }) } }
    }
}

@Composable fun KeyCard(p: Provider) {
    val s = App.i.settings; var open by remember { mutableStateOf(false) }
    var k by remember { mutableStateOf(s.key(p)) }; var b by remember { mutableStateOf(s.base(p)) }; var m by remember { mutableStateOf(s.model(p)) }; var vm by remember { mutableStateOf(s.visionModel(p)) }
    Card(Modifier.padding(vertical = 4.dp).fillMaxWidth().clickable { open = !open }) { Column(Modifier.padding(12.dp)) {
        Text("${p.title} ${if (k.isNotBlank()) "✓" else ""}", fontWeight = FontWeight.Medium)
        if (WebChat.supported(p)) Text(if (s.webLogged(p)) "🌐 Веб-вход выполнен — ключ не обязателен" else "🌐 Можно без ключа: войдите в веб-чат", fontSize = 12.sp)
        if (open) {
            if (WebChat.supported(p)) { val c = LocalContext.current
                OutlinedButton({ c.startActivity(Intent(c, ru.vorontsov.coach.WebLoginActivity::class.java).putExtra("p", p.name)) }) { Text("Войти в веб-чат ${p.title} (без ключа)") } }
            OutlinedTextField(k, { k = it; s.setKey(p, it) }, Modifier.fillMaxWidth(), label = { Text(if (p == Provider.GIGACHAT) "Ключ авторизации (Base64), необязательно" else "API-ключ (необязательно при веб-входе)") }, singleLine = true)
            OutlinedTextField(b, { b = it; s.setBase(p, it) }, Modifier.fillMaxWidth(), label = { Text("Адрес API") }, singleLine = true)
            OutlinedTextField(m, { m = it; s.setModel(p, it) }, Modifier.fillMaxWidth(), label = { Text("Модель чата") }, singleLine = true)
            if (p != Provider.DEEPSEEK) OutlinedTextField(vm, { vm = it; s.setVisionModel(p, it) }, Modifier.fillMaxWidth(), label = { Text("Модель для фото") }, singleLine = true)
        }
    } }
}
