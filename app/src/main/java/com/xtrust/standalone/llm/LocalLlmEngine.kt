package com.xtrust.standalone.llm

interface LocalLlmEngine {
    suspend fun initialize(modelPath: String)
    suspend fun generate(prompt: String): String
    suspend fun sendChatMessage(message: String): String
    fun resetChat()
    fun close()
    val isReady: Boolean
}
