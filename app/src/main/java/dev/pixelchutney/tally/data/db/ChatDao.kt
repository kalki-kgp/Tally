package dev.pixelchutney.tally.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One row per saved conversation, newest first. */
data class ChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

@Dao
interface ChatDao {

    @Insert
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chats SET updatedAt = :at WHERE id = :chatId")
    suspend fun touch(chatId: Long, at: Long)

    /**
     * A chat is named after its first question, which is set once the question is
     * actually asked rather than at creation — an empty chat has nothing to be
     * named after.
     */
    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    suspend fun rename(chatId: Long, title: String)

    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.updatedAt AS updatedAt,
               COUNT(m.id) AS messageCount
        FROM chats c
        LEFT JOIN chat_messages m ON m.chatId = c.id
        GROUP BY c.id
        HAVING messageCount > 0
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """
    )
    fun summariesFlow(limit: Int = 50): Flow<List<ChatSummary>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(chatId: Long): List<ChatMessageEntity>

    /** Messages go with it: the foreign key cascades. */
    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: Long)

    /** Housekeeping for chats abandoned before a question was ever asked. */
    @Query("DELETE FROM chats WHERE id NOT IN (SELECT DISTINCT chatId FROM chat_messages)")
    suspend fun deleteEmptyChats()
}
