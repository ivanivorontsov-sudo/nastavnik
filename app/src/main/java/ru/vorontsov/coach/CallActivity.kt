package ru.vorontsov.coach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import ru.vorontsov.coach.core.*
import ru.vorontsov.coach.data.Verification
import ru.vorontsov.coach.data.Task
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.io.File

/** Полноэкранный «входящий звонок». Кнопки «Назад» нет — только отложить (лимит) или подтвердить фото с камеры. */
/** Полноэкранный звонок/таймер. Кнопки «Назад» нет. Звонок: отложить (10/15 мин, макс 2) или принять. Таймер: продлить 1 раз, фото с камеры. */
class CallActivity : ComponentActivity() {
    private var local by mutableStateOf("") // camera | praise | ""
    private var taskId by mutableLongStateOf(0L)
    private var praise by mutableStateOf("")
    private var verId by mutableLongStateOf(0L)
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) local = "camera" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (local == "camera") local = "" } })
        taskId = intent.getLongExtra("id", 0).takeIf { it != 0L } ?: CallService.state.value?.taskId ?: 0
        if (intent.getBooleanExtra("camera", false)) local = "camera"
        lifecycleScope.launch { CallService.state.collect { if (it != null) { taskId = it.taskId; if (local == "praise") local = "" } } }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1A12)) { Body() } } }
    }
    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); i.getLongExtra("id", 0).takeIf { it != 0L }?.let { taskId = it }; if (i.getBooleanExtra("camera", false)) local = "camera" }

    @Composable private fun Body() {
        val t by App.i.db.dao().taskFlow(taskId).collectAsState(null)
        val v by App.i.db.dao().lastVerification(taskId).collectAsState(null)
        var offMissed by remember { mutableIntStateOf(0) }
        LaunchedEffect(taskId) { offMissed = withContext(Dispatchers.IO) { App.i.db.dao().offMissedSince(System.currentTimeMillis() - 86_400_000L) } }
        LaunchedEffect(v?.status, v?.id) { if (v?.id == verId && verId != 0L && v?.status == "done") { praise = Praise.random(); local = "praise" } }
        val task = t ?: return
        val scold = CallFlow.scoldText(CallFlow.scoldReasons(task, offMissed))
        when {
            local == "camera" -> CameraStage(task)
            local == "praise" -> PraiseStage(v)
            task.state == "verifying" -> VerifyStage(v)
            task.state == "active" -> TimerStage(task, scold, v)
            task.state == "ringing" -> CallStage(task, scold)
            else -> LaunchedEffect(Unit) { finish() }
        }
    }

    @Composable private fun Header(task: Task, sub: String) {
        Text(sub, color = Color(0xFF9CCC65), fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Text(task.title, fontSize = 24.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        if (task.proof.isNotBlank()) Text("📷 Доказательство: ${task.proof}", color = Color(0xFFB0BEC5), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable private fun ScoldBox(task: Task, scold: String?) {
        if (scold == null) return
        Card(Modifier.padding(top = 12.dp).fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF3E2723))) {
            Text("😠 $scold", Modifier.padding(12.dp), color = Color(0xFFFFCC80)) }
        val sset = App.i.settings
        if (sset.grokHarsh && sset.chatProvider == ru.vorontsov.coach.data.Provider.GROK) {
            val rt by AiWorker.rantText.collectAsState()
            var asked by remember { mutableStateOf(false) }
            LaunchedEffect(rt) { if (asked && rt != null && !rt!!.startsWith("⚠")) speak(rt!!) }
            OutlinedButton({ asked = true; AiWorker.rantText.value = null; Workers.enqueue(this@CallActivity, "rant", task.id) },
                Modifier.padding(top = 8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7043))) {
                Icon(Icons.Default.RecordVoiceOver, null); Spacer(Modifier.width(8.dp))
                Text(if (asked && rt == null) "Грок готовит речь…" else "Прослушать Грока (18+)") }
            if (asked && rt != null) Text(rt!!, color = Color(0xFFFFAB91), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable private fun CallStage(task: Task, scold: String?) {
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(110.dp).scale(pulse).background(Color(0xFF2E7D32), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SelfImprovement, null, Modifier.size(60.dp), tint = Color.White) }
            Spacer(Modifier.height(12.dp))
            Text("Наставник", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Header(task, "Входящий звонок · на задачу ${task.durationMin} мин")
            ScoldBox(task, scold)
            Spacer(Modifier.height(24.dp))
            val left = CallFlow.MAX_SNOOZES - task.snoozes
            Text(if (left > 0) "Отложить (осталось $left из ${CallFlow.MAX_SNOOZES}):" else "Откладывать больше нельзя — только принять", color = Color(0xFF90A4AE))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallFlow.SNOOZE_OPTIONS.forEach { m -> RoundBtn(Icons.Default.Snooze, "На $m мин", Color(0xFFF9A825), left > 0) { CallService.cmd(this@CallActivity, "snooze", m); finish() } }
                RoundBtn(Icons.Default.Call, "Принять", Color(0xFF43A047), true) { CallService.cmd(this@CallActivity, "accept") }
            }
            Spacer(Modifier.height(16.dp))
            Text("Сбросить звонок нельзя.", color = Color(0xFF607D8B), fontSize = 12.sp)
        }
    }

    @Composable private fun TimerStage(task: Task, scold: String?, v: Verification?) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
        val left = (task.deadline - now).coerceAtLeast(0) / 1000
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Header(task, if (task.extended) "Время продлено (последний раз)" else "Задача в работе")
            Text("%02d:%02d".format(left / 60, left % 60), fontSize = 72.sp, color = if (left < 60) Color(0xFFEF5350) else Color.White, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 16.dp))
            LinearProgressIndicator({ 1f - left / (task.durationMin * 60f * (if (task.extended) 2 else 1)) }, Modifier.fillMaxWidth())
            if (v?.status == "rejected" || v?.status == "error") Card(Modifier.padding(top = 12.dp).fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF4E342E))) {
                Text("❌ Прошлое фото не засчитано: ${v.reason}", Modifier.padding(12.dp), color = Color(0xFFFFCCBC)) }
            ScoldBox(task, scold)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundBtn(Icons.Default.MoreTime, if (task.extended) "Продление\nиспользовано" else "Продлить\n+${task.durationMin} мин", Color(0xFF5C6BC0), !task.extended) {
                    lifecycleScope.launch(Dispatchers.IO) { (CallFlow.extend(App.i.db.dao().task(task.id)!!) as? CallFlow.R.Ok)?.t?.let { App.i.db.dao().upsertTask(it); Scheduler.set(this@CallActivity, it) } } }
                RoundBtn(Icons.Default.PhotoCamera, "Выполнено —\nфото", Color(0xFF43A047), true) {
                    if (ContextCompat.checkSelfPermission(this@CallActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) local = "camera" else camPerm.launch(Manifest.permission.CAMERA) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Не успеете за ${task.durationMin * 2} мин (с продлением) — наставник позвонит снова.", color = Color(0xFF90A4AE), fontSize = 13.sp, textAlign = TextAlign.Center)
            TextButton({ finish() }) { Text("Свернуть (таймер идёт)") }
        }
    }

    @Composable private fun PraiseStage(v: Verification?) {
        LaunchedEffect(Unit) { delay(6000); finish() }
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.EmojiEvents, null, Modifier.size(110.dp), tint = Color(0xFFFFD54F))
            Text("Засчитано!", fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text(praise, fontSize = 20.sp, color = Color(0xFFC5E1A5), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
            Text("Уверенность ИИ ${((v?.confidence ?: 0.0) * 100).toInt()}% · ${v?.reason ?: ""}", color = Color(0xFF90A4AE), textAlign = TextAlign.Center, fontSize = 13.sp)
            TextButton({ finish() }, Modifier.padding(top = 16.dp)) { Text("Закрыть") }
        }
    }

    @Composable private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(onClick, Modifier.size(76.dp), enabled = enabled, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) {
                Icon(icon, null, Modifier.size(36.dp), tint = Color.White) }
            Text(label, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }

    private var tts: android.speech.tts.TextToSpeech? = null
    /** Воспроизведение только по нажатию кнопки. Звонок на время речи приглушается. */
    private fun speak(text: String) {
        CallService.quietSound(this)
        tts?.shutdown()
        tts = android.speech.tts.TextToSpeech(this) { ok -> if (ok == 0) { tts?.language = java.util.Locale("ru"); tts?.speak(text, 0, null, "rant") } }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private var capture: ImageCapture? = null

    @Composable private fun CameraStage(task: Task) {
        var busy by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            AndroidView({ ctx ->
                PreviewView(ctx).also { pv ->
                    val f = ProcessCameraProvider.getInstance(ctx)
                    f.addListener({
                        val p = f.get(); val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetResolution(android.util.Size(1280, 960)).build()
                        p.unbindAll()
                        val sel = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        runCatching { p.bindToLifecycle(this@CallActivity, sel, prev, capture) }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xAA000000)).padding(16.dp)) {
                Text("Сфотографируйте результат", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Только живой снимок с камеры — галерея недоступна. ${task.proof}", color = Color(0xFFCFD8DC))
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ local = "" }) { Text("Назад", color = Color.White) }
                FilledIconButton({ if (!busy) { busy = true; shoot { busy = false } } }, Modifier.size(84.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)) {
                    if (busy) CircularProgressIndicator(Modifier.size(32.dp)) else Icon(Icons.Default.Camera, null, Modifier.size(44.dp), tint = Color.Black) }
            }
        }
    }

    private fun shoot(done: () -> Unit) {
        val cap = capture ?: return done()
        val dir = File(filesDir, "proofs").apply { mkdirs() }
        val f = File(dir, "p_${System.currentTimeMillis()}.jpg")
        cap.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) { done(); submit(f) }
            override fun onError(e: ImageCaptureException) { done(); android.widget.Toast.makeText(this@CallActivity, "Ошибка камеры: ${e.message}", 1).show() }
        })
    }

    private fun submit(f: File) {
        val tid = taskId
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = App.i.db.dao()
                val t = dao.task(tid) ?: return@withContext
                val r = CallFlow.startVerify(t) as? CallFlow.R.Ok ?: return@withContext
                verId = dao.addVerification(Verification(taskId = tid, photo = f.absolutePath, at = System.currentTimeMillis()))
                dao.upsertTask(r.t) // будильник остаётся на дедлайне: если время выйдет во время проверки — перезвон через 2 мин
            }
            local = ""; CallService.quiet(this@CallActivity)
            if (verId != 0L) Workers.enqueue(this@CallActivity, "verify", verId)
        }
    }

    @Composable private fun VerifyStage(v: Verification?) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(72.dp)); Spacer(Modifier.height(24.dp))
            Text("ИИ проверяет фото…", fontSize = 22.sp, color = Color.White)
            Text("Проверка идёт в фоне — можно заблокировать экран. Нет сети? Фото в очереди и уйдёт, когда сеть появится. Не подтвердится — таймер продолжится (или звонок повторится, если время вышло).", color = Color(0xFF90A4AE), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(24.dp)); TextButton({ finish() }) { Text("Свернуть") }
        }
    }
}
