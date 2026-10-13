package ru.vorontsov.coach.core

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.serialization.json.*
import ru.vorontsov.coach.R
import ru.vorontsov.coach.ai.*
import ru.vorontsov.coach.ai.LenientJson.str
import ru.vorontsov.coach.ai.LenientJson.num
import ru.vorontsov.coach.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** Все запросы к ИИ — только здесь, в WorkManager: работают при закрытом приложении и заблокированном экране,
 *  ждут сеть (офлайн-очередь), повторяются с экспоненциальной задержкой. */
object Workers {
    fun enqueue(c: Context, type: String, id: Long = 0) {
        val req = OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(workDataOf("type" to type, "id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("ai").build()
        WorkManager.getInstance(c).enqueueUniqueWork("$type-$id", ExistingWorkPolicy.REPLACE, req)
    }
    fun scheduleCheckins(c: Context) {
        val h = App.i.settings.checkinHour
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        val req = PeriodicWorkRequestBuilder<CheckinWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(cal.timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("checkin", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class CheckinWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result { Workers.enqueue(applicationContext, "checkin"); return Result.success() }
}

class AiWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    companion object { val rantText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    private suspend fun rant(taskId: Long) {
        val t = dao.task(taskId)
        val facts = "Задача: ${t?.title}. Пропущено звонков: ${dao.missedCount()}. Откладываний сейчас: ${t?.snoozes ?: 0}. Серия: ${Stats.streak(dao.completionsNow())} дн."
        rantText.value = ai.chat(Prompts.RANT_SYSTEM, listOf(ChatMsg("user", facts))).trim()
    }
    private val dao = App.i.db.dao(); private val s = App.i.settings; private val ai = AiClient(s, applicationContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_BG).setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Наставник думает…").setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifier.NID_BG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(Notifier.NID_BG, n)
    }

    override suspend fun doWork(): Result {
        val type = inputData.getString("type") ?: return Result.failure()
        val id = inputData.getLong("id", 0)
        val webP = if (type == "verify") s.visionProvider else s.chatProvider
        if (s.key(webP).isBlank() && WebChat.supported(webP)) runCatching { setForeground(getForegroundInfo()) } // веб-чат: держим процесс живым
        return try {
            when (type) { "verify" -> verify(id); "plan" -> plan(id); "chat" -> chat(id); "reflect" -> reflect(id); "checkin" -> checkin(); "rant" -> rant(id) }
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < 6) Result.retry() else { fail(type, id, e.message ?: "ошибка"); Result.failure() }
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else { fail(type, id, e.toString()); Result.failure() }
        }
    }

    private suspend fun context(): String {
        val goals = dao.activeGoals(); val comps = dao.completionsNow()
        val sb = StringBuilder("ФАКТЫ О ПРОГРЕССЕ:\n")
        goals.forEach { g -> sb.append("• Цель «${g.title}» (зачем: ${g.why}; препятствие: ${g.obstacle}; план если-то: ${g.ifThen}). Выполнено подтверждённых задач: ${comps.count { it.goalId == g.id }}\n") }
        sb.append("Серия дней подряд: ${Stats.streak(comps)}. Сегодня выполнено: ${comps.count { it.day == today() }}.\n")
        dao.lastReflections().take(3).forEach { sb.append("Рефлексия ${it.day}: настроение ${it.mood}/5 — ${it.text.take(150)}\n") }
        return sb.toString()
    }

    private suspend fun chat(msgId: Long) {
        val pending = dao.msg(msgId) ?: return
        val hist = dao.lastMsgs(16).reversed().filter { !it.pending && it.role != "system" }.map { ChatMsg(if (it.role == "user") "user" else "assistant", it.text) }
        val sys = Prompts.coachSystem(s.coachStyle, s.name) + "\n\n" + context()
        val lastUser = dao.lastMsgs(16).firstOrNull { it.role == "user" }
        val ans = if (lastUser != null && lastUser.photo.isNotBlank() && File(lastUser.photo).exists())
            ai.vision(sys, hist.dropLast(1).takeLast(6).joinToString("\n") { (if (it.role == "user") "Я: " else "Наставник: ") + it.content } + "\nЯ (с фото): " + lastUser.text, File(lastUser.photo))
        else ai.chat(sys, hist)
        dao.updMsg(pending.copy(text = ans.trim(), pending = false, at = System.currentTimeMillis()))
        if (!App.visible) Notifier.post(applicationContext, 70, Notifier.CH_COACH, "Наставник ответил", ans.take(200), "coach")
    }

    private suspend fun checkin() {
        if (dao.activeGoals().isEmpty()) return
        val ans = ai.chat(Prompts.coachSystem(s.coachStyle, s.name) + "\n\n" + context(),
            listOf(ChatMsg("user", "[проверка] Напиши мне короткое проактивное сообщение-чекин: оцени прогресс за сегодня и задай один вопрос.")))
        dao.addMsg(Msg(role = "assistant", text = ans.trim()))
        Notifier.post(applicationContext, 71, Notifier.CH_COACH, "Наставник на связи", ans.take(200), "coach")
    }

    private suspend fun reflect(rid: Long) {
        val r = dao.reflection(rid) ?: return
        val ans = ai.chat(Prompts.coachSystem(s.coachStyle, s.name) + "\n\n" + context(),
            listOf(ChatMsg("user", "Вечерняя рефлексия. Настроение ${r.mood}/5. Что было: ${r.text}. Дай обратную связь и один шаг на завтра.")))
        dao.upsertReflection(r.copy(aiFeedback = ans.trim()))
        Notifier.post(applicationContext, 72, Notifier.CH_COACH, "Отзыв на рефлексию", ans.take(200), "review")
    }

    private suspend fun plan(goalId: Long) {
        val g = dao.goal(goalId) ?: return
        val planText = ("Цель: ${g.title}\nЗачем (G): ${g.why}\nТекущая ситуация (R): ${g.reality}\nВарианты (O): ${g.options}\nОбязательство (W): ${g.will}\nПрепятствие (WOOP): ${g.obstacle}\nЕсли-то: ${g.ifThen}\nСрок: ${g.deadline}\nМетрика: ${g.metric}")
        val raw = if (g.photo.isNotBlank() && File(g.photo).exists()) ai.vision(Prompts.PLAN_SYSTEM, planText + "\nНа фото — текущая ситуация пользователя (учти её).", File(g.photo))
            else ai.chat(Prompts.PLAN_SYSTEM, listOf(ChatMsg("user", planText)), jsonMode = true)
        val o = LenientJson.extractObject(raw) ?: throw AiException("ИИ вернул не JSON", true)
        val ms = o["milestones"]?.jsonArray ?: throw AiException("Нет вех в ответе", true)
        val ts = o["tasks"]?.jsonArray ?: throw AiException("Нет задач в ответе", true)
        ms.take(8).forEach { val m = it.jsonObject; dao.upsertMilestone(Milestone(goalId = g.id, title = m.str("title").take(120), week = m.num("week")?.toInt() ?: 1)) }
        ts.take(5).forEach {
            val t = it.jsonObject; val tm = Regex("(\\d{1,2}):(\\d{2})").find(t.str("time"))
            val hh = tm?.groupValues?.get(1)?.toInt()?.coerceIn(0, 23) ?: 9; val mm = tm?.groupValues?.get(2)?.toInt()?.coerceIn(0, 59) ?: 0
            val title = t.str("title").take(120); if (title.isNotBlank())
                Scheduler.saveAndSchedule(applicationContext, Task(goalId = g.id, title = title, proof = t.str("proof").take(200), hour = hh, minute = mm, durationMin = (t.num("minutes")?.toInt() ?: 20).coerceIn(2, 180)))
        }
        dao.upsertGoal(g.copy(metric = o.str("smart").ifBlank { g.metric }, status = "active"))
        dao.addMsg(Msg(goalId = g.id, role = "assistant", text = "План для «${g.title}» готов ✅\n${o.str("smart")}\n\n${o.str("advice")}"))
        Notifier.post(applicationContext, 73, Notifier.CH_COACH, "План готов: ${g.title}", "Вех: ${ms.size}, ежедневных задач: ${ts.size}. Звонки поставлены.", "goals")
    }

    /** Проверка фото: неизменяемый промпт, строгая схема, порог уверенности. Только done && confidence ≥ порога закрывает звонок. */
    private suspend fun verify(vid: Long) {
        val v = dao.verification(vid) ?: return
        val t = dao.task(v.taskId) ?: return
        val raw = ai.vision(Prompts.VERIFY_SYSTEM, Prompts.verifyUser(t.title, t.proof), File(v.photo))
        val verdict = Verdict.parse(raw) ?: throw AiException("Ответ ИИ не соответствует схеме {done, confidence, reason}", true)
        val accepted = verdict.done && verdict.confidence >= s.threshold
        dao.updVerification(v.copy(status = if (accepted) "done" else "rejected", confidence = verdict.confidence, reason = verdict.reason))
        if (accepted) {
            dao.addCompletion(Completion(taskId = t.id, goalId = t.goalId, day = today(), at = System.currentTimeMillis(), confidence = verdict.confidence, reason = verdict.reason))
            val n = CallFlow.verified(t, true, System.currentTimeMillis(), Scheduler.nextDaily(t))
            dao.upsertTask(n); Scheduler.set(applicationContext, n)
            Notifier.post(applicationContext, 80, Notifier.CH_COACH, "Засчитано ✅ ${t.title}", "Уверенность ${(verdict.confidence * 100).toInt()}%. ${verdict.reason}", "today")
            runCatching { File(v.photo).delete() }
        } else rejected(t, "Не засчитано (${(verdict.confidence * 100).toInt()}%): ${verdict.reason}")
    }

    private suspend fun rejected(t0: Task, why: String) {
        val t = dao.task(t0.id) ?: t0
        val n = CallFlow.verified(t, false, System.currentTimeMillis(), 0)
        dao.upsertTask(n)
        if (n.state == "ringing") CallService.ring(applicationContext, t.id, why, force = true) // время вышло — снова звонок
        else { Scheduler.set(applicationContext, n); Notifier.post(applicationContext, 81, Notifier.CH_COACH, "Не засчитано: ${t.title}", "$why\nТаймер продолжается — сделайте и сфотографируйте снова.", "today") }
    }

    private suspend fun fail(type: String, id: Long, err: String) {
        when (type) {
            "chat" -> dao.msg(id)?.let { dao.updMsg(it.copy(text = "⚠ Не удалось получить ответ: $err", pending = false)) }
            "verify" -> dao.verification(id)?.let { v -> dao.updVerification(v.copy(status = "error", reason = err)); dao.task(v.taskId)?.let { rejected(it, "Проверка не удалась: $err. Сделайте фото ещё раз.") } }
            "rant" -> AiWorker.rantText.value = "⚠ $err"
            "plan" -> Notifier.post(applicationContext, 74, Notifier.CH_COACH, "План не составлен", err, "goals")
        }
    }
}

object Stats {
    fun streak(c: List<Completion>): Int {
        val days = c.map { it.day }.toSet(); val cal = Calendar.getInstance(); val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (f.format(cal.time) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0; while (f.format(cal.time) in days) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }
    fun last7(c: List<Completion>): List<Pair<String, Int>> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US); val d = SimpleDateFormat("EE", Locale("ru"))
        return (6 downTo 0).map { back -> val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }; d.format(cal.time) to c.count { it.day == f.format(cal.time) } }
    }
}
