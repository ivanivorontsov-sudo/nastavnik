package ru.vorontsov.coach.core

import ru.vorontsov.coach.data.Task

/**
 * Чистая машина состояний звонка (покрыта unit-тестами).
 * ringing --отложить(10|15, макс 2)--> idle(перезвон) ; ringing --принять--> active(таймер = durationMin)
 * active --продлить(1 раз, +durationMin)--> active ; active --таймер истёк (2× время)--> ringing (цикл заново)
 * active --фото--> verifying --ИИ «да»--> idle (закрыто, следующий день) ; --ИИ «нет»--> active (или ringing, если время вышло)
 */
object CallFlow {
    const val MAX_SNOOZES = 2
    val SNOOZE_OPTIONS = listOf(10, 15)
    const val VERIFY_GRACE_MS = 2 * 60_000L

    sealed class R { data class Ok(val t: Task) : R(); data class No(val why: String) : R() }

    fun ring(t: Task): Task = t.copy(state = "ringing", deadline = 0, extended = false)

    fun snooze(t: Task, minutes: Int, now: Long): R = when {
        t.state != "ringing" -> R.No("Звонок не активен")
        minutes !in SNOOZE_OPTIONS -> R.No("Можно отложить только на 10 или 15 минут")
        t.snoozes >= MAX_SNOOZES -> R.No("Лимит откладываний исчерпан ($MAX_SNOOZES)")
        else -> R.Ok(t.copy(state = "idle", snoozes = t.snoozes + 1, nextAt = now + minutes * 60_000L))
    }

    fun accept(t: Task, now: Long): R = if (t.state != "ringing") R.No("Звонок не активен")
        else { val d = now + t.durationMin * 60_000L; R.Ok(t.copy(state = "active", deadline = d, extended = false, nextAt = d)) }

    fun extend(t: Task): R = when {
        t.state != "active" -> R.No("Таймер не запущен")
        t.extended -> R.No("Продлить можно только один раз")
        else -> { val d = t.deadline + t.durationMin * 60_000L; R.Ok(t.copy(deadline = d, extended = true, overtime = true, nextAt = d)) }
    }

    /** Таймер истёк: звонок звонит снова, нужно принять заново. */
    fun expire(t: Task, now: Long): R = if (t.state == "active" && now >= t.deadline) R.Ok(ring(t).copy(overtime = true)) else R.No("Время ещё не вышло")

    fun startVerify(t: Task): R = if (t.state == "active" || t.state == "ringing") R.Ok(t.copy(state = "verifying")) else R.No("Нечего проверять")

    /** Итог проверки. nextDaily — время следующего звонка для ежедневной задачи. */
    fun verified(t: Task, accepted: Boolean, now: Long, nextDaily: Long): Task = when {
        accepted -> t.copy(state = "idle", snoozes = 0, deadline = 0, extended = false, overtime = false,
            nextAt = if (t.daily) nextDaily else 0, enabled = t.daily)
        t.deadline > now -> t.copy(state = "active", nextAt = t.deadline)
        else -> ring(t).copy(overtime = true)
    }

    /** Будильник на задачу сработал — что делать в текущем состоянии. */
    fun onAlarm(t: Task, now: Long): Task? = when (t.state) {
        "active" -> (expire(t, now) as? R.Ok)?.t
        "verifying" -> if (t.deadline in 1..now) null else null // проверка идёт — ждём результата (перезвон отложен)
        else -> ring(t)
    }

    /** Причины «поругать». */
    fun scoldReasons(t: Task, missedWhileOff: Int): List<String> = buildList {
        if (missedWhileOff > 0) add("пропущено звонков, пока телефон был выключен: $missedWhileOff")
        if (t.snoozes >= 2) add("звонок отложен ${t.snoozes} раза")
        if (t.overtime) add(if (t.extended) "понадобилось продление времени" else "время на задачу вышло, а результата нет")
    }

    fun scoldText(r: List<String>): String? = if (r.isEmpty()) null else
        "Так не пойдёт: " + r.joinToString("; ") + ". Без отговорок — делай сейчас."
}

object Praise {
    val variants = listOf(
        "Отлично! Слово сдержал — так и строятся привычки.", "Есть! Ещё один кирпич в фундамент твоей цели.", "Красавчик! Сделано, а не отложено.",
        "Вот это дисциплина! Будущий ты скажет спасибо.", "Задача закрыта. Чувствуешь, как растёт серия? 🔥", "Сильно! Ты сделал то, что многие только планируют.",
        "Засчитано. Маленькие шаги — большие результаты.", "Браво! Сегодня ты на шаг ближе к цели.", "Сделано честно и с доказательством — уважаю.",
        "Победа дня! Отметь её — ты заслужил.", "Мощно! Так держать и завтра.", "Ты держишь обещание себе — это главное умение.",
        "Готово! Мотивация приходит после действия — и ты это доказал.", "Отлично сработано. Цель уже не мечта, а план в работе.",
        "Ещё один день без пропуска. Серия — твоя суперсила.", "Прекрасно! Ты сильнее своих отговорок.", "Есть контакт! Задача выполнена и подтверждена.",
        "Супер! Прогресс — это сумма таких дней.", "Молодец! Лень проиграла, ты выиграл.", "Это было по-взрослому. Горжусь тобой!",
        "Блестяще! Ещё немного — и это станет автоматической привычкой.", "Ты сделал это! Запомни это чувство.", "Чётко! Наставник доволен.",
        "Огонь! Вот так и выглядит движение к цели.", "Выполнено! Каждый такой день — инвестиция в себя.", "Так держать — ты в ритме!",
        "Сделал — и не важно, хотелось или нет. Это и есть характер.", "Задача побеждена. Следующая — на очереди, но сейчас гордись!",
        "Ура! Подтверждено фото и ИИ — не придраться.", "Великолепно. Ты становишься человеком, который доводит до конца."
    )
    fun random(r: kotlin.random.Random = kotlin.random.Random.Default) = variants[r.nextInt(variants.size)]
}
