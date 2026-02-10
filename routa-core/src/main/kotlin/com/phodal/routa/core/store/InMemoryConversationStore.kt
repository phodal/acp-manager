package com.phodal.routa.core.store

import com.phodal.routa.core.model.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [ConversationStore].
 */
class InMemoryConversationStore : ConversationStore {

    private val conversations = ConcurrentHashMap<String, MutableList<Message>>()

    override suspend fun append(message: Message) {
        conversations.getOrPut(message.agentId) { mutableListOf() }.add(message)
    }

    override suspend fun getConversation(agentId: String): List<Message> {
        return conversations[agentId]?.toList() ?: emptyList()
    }

    override suspend fun getLastN(agentId: String, n: Int): List<Message> {
        val msgs = conversations[agentId] ?: return emptyList()
        return msgs.takeLast(n)
    }

    override suspend fun getByTurnRange(agentId: String, startTurn: Int, endTurn: Int): List<Message> {
        val msgs = conversations[agentId] ?: return emptyList()
        return msgs.filter { msg ->
            val turn = msg.turn ?: return@filter false
            turn in startTurn..endTurn
        }
    }

    override suspend fun getMessageCount(agentId: String): Int {
        return conversations[agentId]?.size ?: 0
    }

    override suspend fun deleteConversation(agentId: String) {
        conversations.remove(agentId)
    }
}
