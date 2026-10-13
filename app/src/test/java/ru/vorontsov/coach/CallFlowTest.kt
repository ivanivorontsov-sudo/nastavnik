package ru.vorontsov.coach

import org.junit.Assert.*
import org.junit.Test
import ru.vorontsov.coach.ai.Verdict
import ru.vorontsov.coach.core.CallFlow
import ru.vorontsov.coach.core.CallFlow.R
import ru.vorontsov.coach.core.Praise
import ru.vorontsov.coach.data.Task

class CallFlowTest {
    private val m = 60_000L
    private fun t() = CallFlow.ring(Task(id = 1, goalId = 1, title = "Пробежка", hour = 9, minute = 0, durationMin = 20))
    private fun ok(r: R) = (r as R.Ok).t

    @Test fun snoozeLimitTwoAndOnly10or15() {
        var x = t()
        assertTrue(CallFlow.snooze(x, 5, 0) is R.No)
        x = CallFlow.ring(ok(CallFlow.snooze(x, 10, 0))); assertEquals(1, x.snoozes)
        x = CallFlow.ring(ok(CallFlow.snooze(x, 15, 0))); assertEquals(2, x.snoozes)
        assertTrue(CallFlow.snooze(x, 10, 0) is R.No)
    }
    @Test fun snoozeSetsNextRing() { assertEquals(15 * m, ok(CallFlow.snooze(t(), 15, 0)).nextAt) }
    @Test fun acceptStartsTimer() { val a = ok(CallFlow.accept(t(), 1000)); assertEquals("active", a.state); assertEquals(1000 + 20 * m, a.deadline) }
    @Test fun extendOnceDoublesTime() {
        val a = ok(CallFlow.accept(t(), 0)); val e = ok(CallFlow.extend(a))
        assertEquals(40 * m, e.deadline); assertTrue(e.extended); assertTrue(CallFlow.extend(e) is R.No)
    }
    @Test fun expireRingsAgainAndRequiresAccept() {
        val e = ok(CallFlow.extend(ok(CallFlow.accept(t(), 0))))
        assertTrue(CallFlow.expire(e, 39 * m) is R.No)
        val r = ok(CallFlow.expire(e, 40 * m)); assertEquals("ringing", r.state); assertTrue(r.overtime); assertFalse(r.extended)
        assertEquals("active", ok(CallFlow.accept(r, 41 * m)).state)
    }
    @Test fun verifySuccessCloses() {
        val v = ok(CallFlow.startVerify(ok(CallFlow.accept(t(), 0))))
        val d = CallFlow.verified(v, true, 5 * m, 999); assertEquals("idle", d.state); assertEquals(999, d.nextAt); assertEquals(0, d.snoozes)
    }
    @Test fun verifyFailContinuesTimerOrRings() {
        val v = ok(CallFlow.startVerify(ok(CallFlow.accept(t(), 0))))
        assertEquals("active", CallFlow.verified(v, false, 5 * m, 0).state)
        assertEquals("ringing", CallFlow.verified(v, false, 25 * m, 0).state)
    }
    @Test fun alarmWhileVerifyingDoesNotRing() { assertNull(CallFlow.onAlarm(t().copy(state = "verifying", deadline = 1), 10)) }
    @Test fun scoldReasons() {
        assertTrue(CallFlow.scoldReasons(t(), 0).isEmpty())
        assertEquals(1, CallFlow.scoldReasons(t(), 2).size)
        assertEquals(2, CallFlow.scoldReasons(t().copy(snoozes = 2, overtime = true), 0).size)
        assertNotNull(CallFlow.scoldText(listOf("x")))
    }
    @Test fun praiseVaried() { assertTrue(Praise.variants.toSet().size >= 25) }
    @Test fun verdictStrictSchema() {
        assertEquals(true, Verdict.parse("```json\n{\"done\": true, \"confidence\": 0.9, \"reason\": \"ok\",}\n```")?.done)
        assertEquals(0.8, Verdict.parse("Ответ: {\"done\":\"да\",\"confidence\":80,\"reason\":\"x\"}")!!.confidence, 1e-9)
        assertNull(Verdict.parse("{\"done\": true}"))
        assertNull(Verdict.parse("{\"confidence\": 0.9}"))
        assertNull(Verdict.parse("я думаю да"))
    }
}
