package com.mythara.lifeline

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
 * One row per camera-captured photo in the user's life timeline.
 *
 * Storage rules:
 *  - We persist METADATA + the captioned text only. The actual photo
 *    bytes stay in MediaStore (the user's gallery); we just keep a
 *    URI / mediaStoreId pointer so the UI can re-decode the image
 *    on demand.
 *  - Cross-device sync ships rows of THIS table (caption + ts + lat/
 *    lng + device id) to the memory repo so the user's timeline
 *    follows them. Raw pixels NEVER leave the device they were taken
 *    on — when device A scrolls back to a photo taken on device B,
 *    it sees the caption + date + device label but the image
 *    placeholder reads "photo on phone-B (not on this device)".
 *
 * Dedup:
 *  - Within one device: unique on (deviceId, mediaStoreId). MediaStore
 *    IDs are stable per install but get reassigned across factory
 *    resets — that's fine, a wipe loses the local row, the synced row
 *    survives.
 *  - Across devices: a contentHash field (sha-256 of the file's first
 *    256KB) lets us recognise the same photo if it ended up on two
 *    devices via cloud backup. NULLable because hashing every image
 *    on import is expensive; computed lazily on first sync.
 */
@Entity(
    tableName = "lifeline_entries",
    indices = [
        Index(value = ["device_id", "media_store_id"], unique = true),
        Index(value = ["taken_ms"]),
        Index(value = ["caption_status"]),
        Index(value = ["content_hash"]),
    ],
)
data class LifelineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "bucket") val bucket: String,
    @ColumnInfo(name = "taken_ms") val takenMs: Long,
    @ColumnInfo(name = "added_ms") val addedMs: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    @ColumnInfo(name = "lat") val lat: Double? = null,
    @ColumnInfo(name = "lng") val lng: Double? = null,
    @ColumnInfo(name = "place_label") val placeLabel: String? = null,
    /** [LifelineCaptionStatus.name] */
    @ColumnInfo(name = "caption_status") val captionStatus: String = LifelineCaptionStatus.PENDING.name,
    @ColumnInfo(name = "caption_text") val captionText: String? = null,
    @ColumnInfo(name = "caption_model") val captionModel: String? = null,
    @ColumnInfo(name = "captioned_at_ms") val captionedAtMs: Long? = null,
    @ColumnInfo(name = "caption_attempts") val captionAttempts: Int = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
    /** True if this row was hydrated from a cross-device sync rather than scanned locally. */
    @ColumnInfo(name = "is_remote") val isRemote: Boolean = false,
)

enum class LifelineCaptionStatus {
    PENDING,    // newly scanned, waiting for caption
    CAPTIONED,  // caption_text set
    FAILED,     // caption attempt failed, attempts < MAX → will retry
    SKIPPED,    // permanently skipped (out of retries, or user disabled captioning)
}

@Dao
interface LifelineDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: LifelineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LifelineEntity): Long

    @Query("SELECT * FROM lifeline_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): LifelineEntity?

    @Query("SELECT * FROM lifeline_entries WHERE device_id = :deviceId AND media_store_id = :mediaStoreId LIMIT 1")
    suspend fun byLocalRef(deviceId: String, mediaStoreId: Long): LifelineEntity?

    @Query(
        """
        SELECT * FROM lifeline_entries
        ORDER BY taken_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecent(limit: Int = 200): List<LifelineEntity>

    /**
     * Photos taken between (exclusive, inclusive) — used by the chat
     * scrollback to interleave timeline cards with messages. UI calls
     * with a window matching the chat history's loaded slice.
     */
    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE taken_ms > :fromMs AND taken_ms <= :toMs
        ORDER BY taken_ms ASC
        """,
    )
    suspend fun listBetween(fromMs: Long, toMs: Long): List<LifelineEntity>

    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE caption_status = :pending AND caption_attempts < :maxAttempts
        ORDER BY taken_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listPending(
        limit: Int = 20,
        pending: String = LifelineCaptionStatus.PENDING.name,
        maxAttempts: Int = 4,
    ): List<LifelineEntity>

    /** Rows whose caption changed since the last sync; used by MemorySync to ship new + edited. */
    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE is_remote = 0 AND (synced_at_ms IS NULL OR captioned_at_ms > synced_at_ms)
        ORDER BY taken_ms ASC
        """,
    )
    suspend fun listUnsynced(): List<LifelineEntity>

    @Query("UPDATE lifeline_entries SET synced_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, nowMs: Long)

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :captioned, caption_text = :text,
            caption_model = :model, captioned_at_ms = :nowMs
        WHERE id = :id
        """,
    )
    suspend fun markCaptioned(
        id: Long, text: String, model: String, nowMs: Long,
        captioned: String = LifelineCaptionStatus.CAPTIONED.name,
    )

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :status, caption_attempts = caption_attempts + 1,
            caption_text = COALESCE(caption_text, :failureNote)
        WHERE id = :id
        """,
    )
    suspend fun markFailed(
        id: Long, failureNote: String,
        status: String = LifelineCaptionStatus.FAILED.name,
    )

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :skipped, caption_text = COALESCE(caption_text, :note)
        WHERE id = :id
        """,
    )
    suspend fun markSkipped(
        id: Long, note: String,
        skipped: String = LifelineCaptionStatus.SKIPPED.name,
    )

    @Query("SELECT COUNT(*) FROM lifeline_entries")
    suspend fun total(): Int

    @Query("SELECT MAX(added_ms) FROM lifeline_entries WHERE is_remote = 0")
    suspend fun lastScannedAddedMs(): Long?

    /** Live observation for the chat surface's interleaved timeline. */
    @Query("SELECT * FROM lifeline_entries ORDER BY taken_ms ASC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LifelineEntity>>
}

@Database(entities = [LifelineEntity::class], version = 1, exportSchema = false)
abstract class LifelineDb : RoomDatabase() {
    abstract fun dao(): LifelineDao
}

@Singleton
class LifelineRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: LifelineDb = Room.databaseBuilder(
        ctx, LifelineDb::class.java, "mythara_lifeline.db",
    ).build()
    val dao: LifelineDao = db.dao()
}
