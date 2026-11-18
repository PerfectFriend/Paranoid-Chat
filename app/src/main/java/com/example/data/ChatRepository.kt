package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val chatDao: ChatDao,
    private val shredLocalFile: (String?) -> Boolean = { false },
    private val encryptMessageText: (String, String) -> String = { _, text -> text },
    private val decryptMessageText: (String, String) -> String = { _, text -> text }
) {

    val allContacts: Flow<List<Contact>> = chatDao.getAllContacts()
    val allMessages: Flow<List<Message>> = chatDao.getAllMessages()
        .map { list ->
            list.map { msg -> msg.copy(text = decryptMessageText(msg.contactId, msg.text)) }
        }

    fun getMessagesForContact(contactId: String): Flow<List<Message>> {
        return chatDao.getMessagesForContact(contactId)
            .map { list ->
                list.map { msg -> msg.copy(text = decryptMessageText(msg.contactId, msg.text)) }
            }
    }

    suspend fun insertContact(contact: Contact) {
        chatDao.insertContact(contact)
    }

    suspend fun deleteContact(contact: Contact) {
        chatDao.deleteContact(contact)
    }

    suspend fun insertMessage(message: Message) {
        val sealedText = encryptMessageText(message.contactId, message.text)
        chatDao.insertMessage(message.copy(text = sealedText))
    }

    suspend fun deleteMessage(id: Long) {
        chatDao.deleteMessage(id)
    }

    suspend fun pruneExpiredMessages(currentTime: Long): Int {
        val expiredMedia = chatDao.getExpiredMediaUris(currentTime)
        expiredMedia.forEach { uri -> shredLocalFile(uri) }
        return chatDao.pruneExpiredMessages(currentTime)
    }

    suspend fun clearConversation(contactId: String): Int {
        val mediaUris = chatDao.getMediaUrisForContact(contactId)
        mediaUris.forEach { uri -> shredLocalFile(uri) }
        return chatDao.deleteMessagesForContact(contactId)
    }

    suspend fun shredContactAndLane(contact: Contact): Int {
        val removedMessages = clearConversation(contact.id)
        chatDao.deleteContact(contact)
        return removedMessages
    }

    suspend fun panicPurge() {
        chatDao.clearAllMessages()
        chatDao.clearAllContacts()
    }
}
