package ru.vorontsov.coach.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val why: String = "", val reality: String = "", val options: String = "",
    val will: String = "", val obstacle: String = "", val ifThen: String = "", val deadline: String = "",
    val metric: String = "", val photo: String = "", val status: String = "active", // planning | active | done
    val createdAt: Long = System.currentTimeMillis()
)
@Entity data class Milestone(@PrimaryKey(autoGenerate = true) val id: Long = 0, val goalId: Long, val title: String, val week: Int, val done: Boolean = false)

/** Задача-звонок. Ежедневная если daily=true. */
@Entity data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val goalId: Long, val title: String,
    val proof: String = "", // что должно быть на фото
    val hour: Int, val minute: Int, val daily: Boolean = true,
    val nextAt: Long = 0, val snoozes: Int = 0, val maxSnoozes: Int = 3,
    val state: String = "idle", // idle | ringing | active | verifying
    val enabled: Boolean = true,
    val durationMin: Int = 20, val deadline: Long = 0, val extended: Boolean = false,
    val overtime: Boolean = false // в этом цикле продлевал или просрочил таймер
)
@Entity data class Completion(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val goalId: Long, val day: String, val at: Long, val confidence: Double, val reason: String)
@Entity data class Verification(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val photo: String, val at: Long,
    val status: String = "queued", // queued | done | rejected | error
    val confidence: Double = 0.0, val reason: String = "")
@Entity data class Msg(@PrimaryKey(autoGenerate = true) val id: Long = 0, val goalId: Long = 0, val role: String, val text: String,
    val at: Long = System.currentTimeMillis(), val pending: Boolean = false, val photo: String = "")
@Entity data class Reflection(@PrimaryKey(autoGenerate = true) val id: Long = 0, val day: String, val mood: Int, val text: String, val aiFeedback: String = "")
@Entity data class MissedCall(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val title: String, val at: Long, val kind: String = "noanswer") // off | noanswer

@Dao interface Dao_ {
    @Query("SELECT * FROM Goal ORDER BY createdAt DESC") fun goals(): Flow<List<Goal>>
    @Query("SELECT * FROM Goal WHERE id=:id") suspend fun goal(id: Long): Goal?
    @Query("SELECT * FROM Goal WHERE status='active'") suspend fun activeGoals(): List<Goal>
    @Upsert suspend fun upsertGoal(g: Goal): Long
    @Query("DELETE FROM Goal WHERE id=:id") suspend fun delGoal(id: Long)
    @Query("SELECT * FROM Milestone WHERE goalId=:g ORDER BY week") fun milestones(g: Long): Flow<List<Milestone>>
    @Query("SELECT * FROM Milestone WHERE goalId=:g ORDER BY week") suspend fun milestonesNow(g: Long): List<Milestone>
    @Upsert suspend fun upsertMilestone(m: Milestone): Long
    @Query("SELECT * FROM Task ORDER BY hour, minute") fun tasks(): Flow<List<Task>>
    @Query("SELECT * FROM Task WHERE goalId=:g ORDER BY hour, minute") suspend fun tasksOf(g: Long): List<Task>
    @Query("SELECT * FROM Task") suspend fun allTasks(): List<Task>
    @Query("SELECT * FROM Task WHERE id=:id") suspend fun task(id: Long): Task?
    @Upsert suspend fun upsertTask(t: Task): Long
    @Query("DELETE FROM Task WHERE id=:id") suspend fun delTask(id: Long)
    @Insert suspend fun addCompletion(c: Completion)
    @Query("SELECT * FROM Completion ORDER BY at DESC") fun completions(): Flow<List<Completion>>
    @Query("SELECT * FROM Completion ORDER BY at DESC") suspend fun completionsNow(): List<Completion>
    @Insert suspend fun addVerification(v: Verification): Long
    @Update suspend fun updVerification(v: Verification)
    @Query("SELECT * FROM Verification WHERE id=:id") suspend fun verification(id: Long): Verification?
    @Query("SELECT * FROM Verification WHERE taskId=:t ORDER BY at DESC LIMIT 1") fun lastVerification(t: Long): Flow<Verification?>
    @Insert suspend fun addMsg(m: Msg): Long
    @Update suspend fun updMsg(m: Msg)
    @Query("SELECT * FROM Msg WHERE id=:id") suspend fun msg(id: Long): Msg?
    @Query("SELECT * FROM Msg ORDER BY at") fun msgs(): Flow<List<Msg>>
    @Query("SELECT * FROM Msg ORDER BY at DESC LIMIT :n") suspend fun lastMsgs(n: Int): List<Msg>
    @Upsert suspend fun upsertReflection(r: Reflection): Long
    @Query("SELECT * FROM Reflection WHERE id=:id") suspend fun reflection(id: Long): Reflection?
    @Query("SELECT * FROM Reflection ORDER BY day DESC") fun reflections(): Flow<List<Reflection>>
    @Query("SELECT * FROM Reflection ORDER BY day DESC LIMIT 7") suspend fun lastReflections(): List<Reflection>
    @Query("SELECT COUNT(*) FROM MissedCall") suspend fun missedCount(): Int
    @Query("SELECT COUNT(*) FROM MissedCall WHERE kind='off' AND at > :since") suspend fun offMissedSince(since: Long): Int
    @Query("SELECT * FROM Task WHERE id=:id") fun taskFlow(id: Long): Flow<Task?>
    @Insert suspend fun addMissed(m: MissedCall)
    @Query("SELECT * FROM MissedCall ORDER BY at DESC LIMIT 50") fun missed(): Flow<List<MissedCall>>
}

@Database(entities = [Goal::class, Milestone::class, Task::class, Completion::class, Verification::class, Msg::class, Reflection::class, MissedCall::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): Dao_
    companion object {
        val M12 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Goal ADD COLUMN photo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Msg ADD COLUMN photo TEXT NOT NULL DEFAULT ''")
            }
        }
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "coach.db").addMigrations(M12).build()
    }
}
