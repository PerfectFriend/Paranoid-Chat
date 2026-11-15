package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val id: String, // SimpleX URI hash or public key
    val displayName: String,
    val onionAddress: String, // e.g. simplex://...onion uri
    val tailscaleIp: String?,   // Node IP if routed via tailscale
    val isBlocked: Boolean = false,
    val publicKey: String,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDelivered: Boolean = true,
    val isRead: Boolean = false,
    // File/Media Transfer support
    val mediaUri: String? = null, // URI / path to the file in app private cache directory
    val mediaType: String? = null, // "IMAGE", "VOICE", "FILE" null if standard text
    val voiceDurationMs: Long = 0L,
    // 24 Hour TTL requirement (System.currentTimeMillis() + 24 * 3600 * 1000)
    val ttlExpiry: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM contacts ORDER BY lastSeenTimestamp DESC")
    fun getAllContacts(): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY timestamp ASC")
    fun getMessagesForContact(contactId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    // Automated 24h expiration query
    @Query("DELETE FROM messages WHERE ttlExpiry <= :currentTime")
    suspend fun pruneExpiredMessages(currentTime: Long): Int

    // Zero-Log / Panic purge of database
    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("DELETE FROM contacts")
    suspend fun clearAllContacts()
}
