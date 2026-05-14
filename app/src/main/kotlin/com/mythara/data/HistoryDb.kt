package com.mythara.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat history persistence. M2 is single-conversation — every turn lands
 * in the same `messages` table ordered by `tsMillis`. Multi-session +
 * search land in a later milestone.
 *
 * Schema is OpenAI-shaped (role/content/toolCallId etc.) so we can replay
 * the conversation directly into MiniMax's chat endpoint without an
 * intermediate translation layer.
 */
@Entity(tableName = "messages")
data class MessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "ts_millis") val tsMillis: Long,
    val role: String,                                     // user | assistant | system | tool
    val content: String?,
    @ColumnInfo(name = "tool_calls_json") val toolCallsJson: String? = null,
    @ColumnInfo(name = "tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    /**
     * Stable per-install id of the device that authored this message.
     * Null for rows from the pre-deviceId schema (legacy local entries).
     * Set on every local insert; preserved verbatim on cross-device
     * memory-sync restore so ChatViewModel can render foreign-device
     * messages as their own card.
     */
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY ts_millis ASC")
    fun observeAll(): Flow<List<MessageRow>>

    @Query("SELECT * FROM messages ORDER BY ts_millis ASC")
    suspend fun listAll(): List<MessageRow>

    /**
     * The most recent [limit] rows, returned oldest-first so the result
     * is a drop-in for [listAll] in the agent loop's request builder.
     * Bounds the context the agent ships to the model — `listAll` is
     * unbounded and a large history (e.g. after a cross-device restore)
     * otherwise 400s MiniMax.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM messages ORDER BY ts_millis DESC LIMIT :limit) ORDER BY ts_millis ASC",
    )
    suspend fun listRecent(limit: Int): List<MessageRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MessageRow): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageRow>)

    @Query("DELETE FROM messages")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Database(entities = [MessageRow::class], version = 2, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun messages(): MessageDao
}

/**
 * v1 → v2 just adds the nullable device_id column. Non-destructive
 * so existing chat history survives the schema bump.
 */
private val MIGRATION_HISTORY_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN device_id TEXT")
    }
}

@Singleton
class HistoryRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: HistoryDb = Room.databaseBuilder(ctx, HistoryDb::class.java, "mythara_history.db")
        .addMigrations(MIGRATION_HISTORY_1_2)
        .build()
    val dao: MessageDao = db.messages()
}
