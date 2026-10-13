package ru.vorontsov.coach.core

import android.app.*
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ru.vorontsov.coach.data.AppDb
import ru.vorontsov.coach.data.Settings

class App : Application() {
    companion object { lateinit var i: App; @Volatile var visible = false }
    val db by lazy { AppDb.create(this) }
    val settings by lazy { Settings(this) }
    override fun onCreate() {
        super.onCreate(); i = this
        Notifier.channels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { visible = true }
            override fun onStop(owner: LifecycleOwner) { visible = false }
        })
        Workers.scheduleCheckins(this)
    }
}

object Notifier {
    const val CH_CALL = "calls2"; const val CH_COACH = "coach"; const val CH_MISSED = "missed"; const val CH_BG = "bg"
    const val NID_CALL = 1; const val NID_BG = 2
    fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    fun channels(c: Context) {
        val call = NotificationChannel(CH_CALL, "Звонки наставника", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Полноэкранные звонки-напоминания"; setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null); enableVibration(false) // звук и вибрацию даёт сервис
        }
        nm(c).createNotificationChannels(listOf(call,
            NotificationChannel(CH_COACH, "Сообщения коуча", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_MISSED, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_BG, "Фоновые запросы к ИИ", NotificationManager.IMPORTANCE_LOW)))
    }
    fun open(c: Context, tab: String = "") = PendingIntent.getActivity(c, tab.hashCode(),
        android.content.Intent(c, ru.vorontsov.coach.MainActivity::class.java).putExtra("tab", tab).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun post(c: Context, id: Int, ch: String, title: String, text: String, tab: String = "") {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33) return
        nm(c).notify(id, androidx.core.app.NotificationCompat.Builder(c, ch).setSmallIcon(ru.vorontsov.coach.R.drawable.ic_stat_alarm)
            .setContentTitle(title).setContentText(text).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open(c, tab)).build())
    }
}
