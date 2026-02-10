package com.phodal.routa.core.store

import com.phodal.routa.core.model.Message

/**
 * Persistent storage for agent conversation histories.
 *
 * Each agent has its own conversation log that can be read
 * by other agents (e.g., for conflict detection or verification).
 */
interface ConversationStore {

    /**
     * Append a message to an agent's conversation.
     */
    suspend fun append(message: Message)

    /**
     * Get the full conversation history for an agent.
     */
    suspend fun getConversation(agentId: String): List<Message>

    /**
     * Get the last N messages for an agent.
     */
    suspend fun getLastN(agentId: String, n: Int): List<Message>

    /**
     * Get messages in a turn range for an agent.
     */
    suspend fun getByTurnRange(agentId: String, startTurn: Int, endTurn: Int): List<Message>

    /**
     * Get the total number of messages for an agent.
     */
    suspend fun getMessageCount(agentId: String): Int

    /**
     * Delete all messages for an agent (used when agent is deleted).
     */
    suspend fun deleteConversation(agentId: String)
}
