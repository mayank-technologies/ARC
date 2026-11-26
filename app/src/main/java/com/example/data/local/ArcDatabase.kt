package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val triggerAtMillis: Long,
    /** NONE, DAILY, WEEKDAYS, WEEKLY */
    val recurrence: String = "NONE",
    /** Comma-separated java.time.DayOfWeek values (1=Mon..7=Sun) if WEEKLY/CUSTOM */
    val weekdaysCsv: String = "",
    val isEnabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String = "Preference",
    val factText: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** USER, ARC, TOOL, SYSTEM */
    val role: String,
    val text: String,
    val contextTag: String = "CASUAL",
    val timestampMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "gallery_photos")
data class GalleryPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val dateIso: String, // YYYY-MM-DD
    val label: String = "",
    val capturedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1 ORDER BY triggerAtMillis ASC")
    suspend fun getAllEnabled(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_facts ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY createdAtMillis DESC")
    suspend fun getAll(): List<MemoryFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: MemoryFactEntity): Long

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestampMillis ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface GalleryPhotoDao {
    @Query("SELECT * FROM gallery_photos ORDER BY capturedAtMillis DESC")
    fun observeAll(): Flow<List<GalleryPhotoEntity>>

    @Query("SELECT * FROM gallery_photos WHERE dateIso = :dateIso ORDER BY capturedAtMillis DESC")
    fun observeByDate(dateIso: String): Flow<List<GalleryPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: GalleryPhotoEntity): Long

    @Query("DELETE FROM gallery_photos WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [
        ReminderEntity::class,
        MemoryFactEntity::class,
        ChatMessageEntity::class,
        GalleryPhotoEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ArcDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatMessageDao
    abstract fun galleryDao(): GalleryPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: ArcDatabase? = null

        fun getInstance(context: Context): ArcDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArcDatabase::class.java,
                    "arc_assistant.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class ArcLocalRepository(private val db: ArcDatabase) {
    val reminders: Flow<List<ReminderEntity>> = db.reminderDao().observeAll()
    val memories: Flow<List<MemoryFactEntity>> = db.memoryDao().observeAll()
    val messages: Flow<List<ChatMessageEntity>> = db.chatDao().observeAll()
    val galleryPhotos: Flow<List<GalleryPhotoEntity>> = db.galleryDao().observeAll()

    suspend fun getAllMemories(): List<MemoryFactEntity> = db.memoryDao().getAll()
    suspend fun getEnabledReminders(): List<ReminderEntity> = db.reminderDao().getAllEnabled()

    suspend fun addReminder(reminder: ReminderEntity): Long = db.reminderDao().insert(reminder)
    suspend fun updateReminder(reminder: ReminderEntity) = db.reminderDao().update(reminder)
    suspend fun deleteReminder(id: Long) = db.reminderDao().deleteById(id)

    suspend fun addMemory(fact: String, category: String = "Preference"): Long {
        val cleaned = fact.trim()
        val existing = db.memoryDao().getAll()
        if (existing.any { it.factText.equals(cleaned, ignoreCase = true) }) {
            return -1L
        }
        return db.memoryDao().insert(MemoryFactEntity(category = category, factText = cleaned))
    }

    suspend fun deleteMemory(id: Long) = db.memoryDao().deleteById(id)
    suspend fun wipeAllMemories() = db.memoryDao().deleteAll()

    suspend fun addMessage(role: String, text: String, contextTag: String = "CASUAL") {
        db.chatDao().insert(ChatMessageEntity(role = role, text = text, contextTag = contextTag))
    }

    suspend fun clearMessages() = db.chatDao().clearAll()

    suspend fun addGalleryPhoto(uriString: String, dateIso: String, label: String = "") {
        db.galleryDao().insert(GalleryPhotoEntity(uriString = uriString, dateIso = dateIso, label = label))
    }

    suspend fun deleteGalleryPhoto(id: Long) = db.galleryDao().deleteById(id)
}
