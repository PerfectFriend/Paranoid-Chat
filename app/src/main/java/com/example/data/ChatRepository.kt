package com.example.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    val allContacts: Flow<List<Contact>> = chatDao.getAllContacts()
    val allMessages: Flow<List<Message>> = chatDao.getAllMessages()

    fun getMessagesForContact(contactId: String): Flow<List<Message>> {
        return chatDao.getMessagesForContact(contactId)
    }

    suspend fun insertContact(contact: Contact) {
        chatDao.insertContact(contact)
    }

    suspend fun deleteContact(contact: Contact) {
        chatDao.deleteContact(contact)
    }

    suspend fun insertMessage(message: Message) {
        chatDao.insertMessage(message)
    }

    suspend fun deleteMessage(id: Long) {
        chatDao.deleteMessage(id)
    }

    suspend fun pruneExpiredMessages(currentTime: Long): Int {
        return chatDao.pruneExpiredMessages(currentTime)
    }

    suspend fun panicPurge() {
        chatDao.clearAllMessages()
        chatDao.clearAllContacts()
    }
}
