package ru.vorontsov.coach.core

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import ru.vorontsov.coach.CallActivity
import ru.vorontsov.coach.R
import ru.vorontsov.coach.data.MissedCall

data class CallState(val taskId: Long, val note: String)

/** «Входящий звонок» наставника: CallStyle + full-screen intent, рингтон, вибрация, поверх экрана блокировки. Сбросить нельзя. */
class CallService : Service() {
    companion object {
        val state = MutableStateFlow<CallState?>(null)
        const val RING_TIMEOUT_MS = 90_000L; const val NOANSWER_RERING_MIN = 5
        fun ring(c: Context, taskId: Long, note: String, force: Boolean = false) =
            ContextCompat.startForegroundService(c, Intent(c, CallService::class.java).setAction("ring").putExtra("id", taskId).putExtra("note", note).putExtra("force", force))
        fun cmd(c: Context, action: String, min: Int = 0) = c.startService(Intent(c, CallService::class.java).setAction(action).putExtra("min", min))
        fun quietSound(c: Context) = cmd(c, "mute")
        fun quiet(c: Context) = cmd(c, "quiet")
    }
    private var player: MediaPlayer? = null; private var vib: Vibrator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val h = Handler(Looper.getMainLooper())
    private val dao get() = App.i.db.dao()
    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "ring" -> start(i.getLongExtra("id", 0), i.getStringExtra("note") ?: "", i.getBooleanExtra("force", false))
            "snooze" -> scope.launch { applyFlow { CallFlow.snooze(it, i.getIntExtra("min", 10), System.currentTimeMillis()) } }
            "accept" -> scope.launch { applyFlow { CallFlow.accept(it, System.currentTimeMillis()) } }
            "quiet" -> stopAll()
            "mute" -> stopSound()
            else -> if (state.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun applyFlow(f: (ru.vorontsov.coach.data.Task) -> CallFlow.R) {
        val id = state.value?.taskId ?: return
        val ok = withContext(Dispatchers.IO) {
            val t = dao.task(id) ?: return@withContext false
            val r = f(t) as? CallFlow.R.Ok ?: return@withContext false
            dao.upsertTask(r.t); Scheduler.set(this@CallService, r.t); true
        }
        if (ok) stopAll()
    }

    private fun start(id: Long, note: String, force: Boolean) {
        fg(build("Наставник звонит", note))
        scope.launch {
            val t = withContext(Dispatchers.IO) { dao.task(id) } ?: run { stopAll(); return@launch }
            val now = System.currentTimeMillis()
            val r = if (force) CallFlow.ring(t) else CallFlow.onAlarm(t, now)
            if (r == null) { // идёт проверка фото — перезвон чуть позже
                withContext(Dispatchers.IO) { val n = t.copy(nextAt = now + CallFlow.VERIFY_GRACE_MS); dao.upsertTask(n); Scheduler.set(this@CallService, n) }
                if (state.value == null) stopAll(); return@launch
            }
            withContext(Dispatchers.IO) { dao.upsertTask(r); Scheduler.cancel(this@CallService, r.id) }
            state.value = CallState(id, note); fg(build(r.title, note.ifBlank { "Нажмите, чтобы ответить" })); sound()
            runCatching { startActivity(Intent(this@CallService, CallActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            h.removeCallbacksAndMessages(null)
            h.postDelayed({ scope.launch { noAnswer() } }, RING_TIMEOUT_MS)
        }
    }

    /** Нет ответа — не закрытие: перезвон через 5 минут (лимит откладываний не тратится). */
    private suspend fun noAnswer() {
        val st = state.value ?: return
        val title = withContext(Dispatchers.IO) {
            val t = dao.task(st.taskId) ?: return@withContext ""
            if (t.state != "ringing") return@withContext null
            val n = t.copy(nextAt = System.currentTimeMillis() + NOANSWER_RERING_MIN * 60_000L, state = "idle")
            dao.upsertTask(n); Scheduler.set(this@CallService, n)
            dao.addMissed(MissedCall(taskId = t.id, title = t.title, at = System.currentTimeMillis())); t.title
        } ?: return
        Notifier.post(this, 60 + st.taskId.toInt(), Notifier.CH_MISSED, "Пропущенный звонок: $title", "Наставник перезвонит через $NOANSWER_RERING_MIN мин.", "today")
        stopAll()
    }

    private fun build(title: String, text: String): Notification {
        val id = state.value?.taskId ?: 0
        val full = PendingIntent.getActivity(this, 1, Intent(this, CallActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val caller = Person.Builder().setName("Наставник").setImportant(true).build()
        // «Отклонить» не сбрасывает звонок, а открывает экран выбора (отложить на 10/15 мин или принять).
        return NotificationCompat.Builder(this, Notifier.CH_CALL).setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, full, full))
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setAutoCancel(false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true).setContentIntent(full).build()
    }
    private fun fg(n: Notification) = runCatching {
        ServiceCompat.startForeground(this, Notifier.NID_CALL, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }
    private fun sound() {
        stopSound()
        vib = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0)) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@CallService, uri); isLooping = true; prepare(); start()
            }
        }
    }
    private fun stopSound() { runCatching { player?.stop(); player?.release() }; player = null; runCatching { vib?.cancel() } }
    private fun stopAll() {
        h.removeCallbacksAndMessages(null); stopSound(); state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stopSound(); scope.cancel(); super.onDestroy() }
}
