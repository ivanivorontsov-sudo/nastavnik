package ru.vorontsov.coach.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import ru.vorontsov.coach.data.MissedCall
import ru.vorontsov.coach.data.Task
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * ВАЖНО: используем ТОЛЬКО setExactAndAllowWhileIdle(RTC_WAKEUP). Никаких setAlarmClock и power-off-alarm API —
 * на некоторых прошивках (realme/ColorOS и др.) будильники setAlarmClock могут включать выключенный телефон.
 * RTC_WAKEUP будит только из сна, но не из выключенного состояния.
 */
object Scheduler {
    fun am(c: Context) = c.getSystemService(AlarmManager::class.java)
    fun canExact(c: Context) = android.os.Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    fun nextDaily(t: Task, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, t.hour); set(Calendar.MINUTE, t.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(c, id.toInt(),
        Intent(c, AlarmReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun set(c: Context, t: Task) {
        am(c).cancel(pi(c, t.id))
        if (!t.enabled || t.nextAt <= 0) return
        if (canExact(c)) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.nextAt, pi(c, t.id))
        else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.nextAt, pi(c, t.id))
    }
    fun cancel(c: Context, id: Long) = am(c).cancel(pi(c, id))

    suspend fun saveAndSchedule(c: Context, t: Task): Task {
        val tt = if (t.nextAt <= System.currentTimeMillis()) t.copy(nextAt = nextDaily(t)) else t
        val id = App.i.db.dao().upsertTask(tt)
        val saved = if (tt.id == 0L) tt.copy(id = id) else tt
        set(c, saved); return saved
    }

    /** После загрузки: пропущенные во время выключения звонки НЕ звонят задним числом — только список «пропущенных» и одно уведомление. */
    suspend fun rescheduleAfterBoot(c: Context, boot: Boolean) {
        val dao = App.i.db.dao(); val now = System.currentTimeMillis(); val missed = mutableListOf<String>()
        for (t in dao.allTasks()) {
            var x = t
            if (x.state != "idle" && boot) x = x.copy(state = "idle", deadline = 0, extended = false, nextAt = if (x.deadline in 1 until now) x.deadline else x.nextAt)
            if (x.enabled && x.nextAt in 1 until now - 60_000) {
                if (boot) { missed += x.title; dao.addMissed(MissedCall(taskId = x.id, title = x.title, at = x.nextAt, kind = "off")) }
                x = x.copy(nextAt = if (x.daily) nextDaily(x, now) else 0, snoozes = 0, enabled = x.daily)
            }
            dao.upsertTask(x); set(c, x)
        }
        if (missed.isNotEmpty()) Notifier.post(c, 50, Notifier.CH_MISSED, "Пропущено звонков: ${missed.size}",
            "Пока телефон был выключен: " + missed.joinToString(", ") + ". Звонить задним числом не будем — следующий звонок по расписанию.", "today")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { CallService.ring(c, i.getLongExtra("id", 0), "") }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync(); val boot = i.action?.contains("BOOT") == true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { Scheduler.rescheduleAfterBoot(c.applicationContext, boot); Workers.scheduleCheckins(c.applicationContext) } finally { pr.finish() }
        }
    }
}
