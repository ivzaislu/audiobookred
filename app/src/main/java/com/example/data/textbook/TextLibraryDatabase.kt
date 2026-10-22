package com.example.data.textbook

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "text_books")
internal data class TextBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalName: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val encoding: String,
    val parserVersion: Int,
    val importedAtMs: Long,
    val lastOpenedAtMs: Long,
    val characterCount: Int,
    val chapterCount: Int,
    val fragmentCount: Int,
)

@Entity(
    tableName = "text_chapters",
    foreignKeys = [ForeignKey(entity = TextBookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "ordinal"], unique = true)],
)
internal data class TextChapterEntity(
    @PrimaryKey val id: String, val bookId: String, val ordinal: Int, val title: String,
    val startOffset: Int, val endOffset: Int, val automatic: Boolean,
)

@Entity(
    tableName = "text_fragments",
    foreignKeys = [ForeignKey(entity = TextChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["chapterId", "ordinal"], unique = true)],
)
internal data class TextFragmentEntity(
    @PrimaryKey val id: String, val chapterId: String, val ordinal: Int,
    val startOffset: Int, val endOffset: Int, val text: String,
)

@Entity(
    tableName = "text_reading_positions",
    foreignKeys = [
        ForeignKey(entity = TextBookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TextFragmentEntity::class, parentColumns = ["id"], childColumns = ["fragmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("fragmentId")],
)
internal data class TextReadingPositionEntity(
    @PrimaryKey val bookId: String, val fragmentId: String,
    val scrollOffsetPx: Int, val updatedAtMs: Long,
)

@Dao
internal interface TextLibraryDao {
    @Query("SELECT * FROM text_books ORDER BY lastOpenedAtMs DESC, importedAtMs DESC, id")
    fun observeBooks(): Flow<List<TextBookEntity>>
    @Query("SELECT * FROM text_books WHERE id = :id") suspend fun book(id: String): TextBookEntity?
    @Query("SELECT * FROM text_chapters WHERE bookId = :bookId ORDER BY ordinal")
    suspend fun chapters(bookId: String): List<TextChapterEntity>
    @Query("SELECT * FROM text_fragments WHERE chapterId = :chapterId ORDER BY ordinal")
    suspend fun fragments(chapterId: String): List<TextFragmentEntity>
    @Query("SELECT f.* FROM text_fragments f INNER JOIN text_chapters c ON c.id = f.chapterId WHERE c.bookId = :bookId ORDER BY c.ordinal, f.ordinal")
    suspend fun allFragments(bookId: String): List<TextFragmentEntity>
    @Query("SELECT f.* FROM text_fragments f INNER JOIN text_chapters c ON c.id = f.chapterId WHERE c.bookId = :bookId AND f.id = :fragmentId")
    suspend fun narrationFragment(bookId: String, fragmentId: String): TextFragmentEntity?
    @Query("SELECT * FROM text_reading_positions WHERE bookId = :bookId")
    suspend fun position(bookId: String): TextReadingPositionEntity?
    @Query("SELECT c.id FROM text_chapters c INNER JOIN text_fragments f ON f.chapterId = c.id WHERE c.bookId = :bookId AND f.id = :fragmentId")
    suspend fun chapterForFragment(bookId: String, fragmentId: String): String?
    @Insert suspend fun insertBook(book: TextBookEntity)
    @Insert suspend fun insertChapters(chapters: List<TextChapterEntity>)
    @Insert suspend fun insertFragments(fragments: List<TextFragmentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosition(position: TextReadingPositionEntity)
    @Query("UPDATE text_books SET lastOpenedAtMs = :now WHERE id = :bookId")
    suspend fun touch(bookId: String, now: Long)
    @Query("DELETE FROM text_books WHERE id = :bookId") suspend fun delete(bookId: String)
}

/** Imported text is durable source content, separate from the audiobook cache/restore journal. */
@Database(
    entities = [TextBookEntity::class, TextChapterEntity::class, TextFragmentEntity::class, TextReadingPositionEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class TextLibraryDatabase : RoomDatabase() {
    abstract fun library(): TextLibraryDao
    companion object {
        fun open(context: Context): TextLibraryDatabase = Room.databaseBuilder(
            context.applicationContext, TextLibraryDatabase::class.java, "abred-text-library.db",
        ).build()
    }
}
