package com.xtrust.standalone.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.xtrust.standalone.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtLlmEngine"
private const val DEFAULT_SYSTEM_PROMPT =
    "You are a concise assistant. Reply in the same language as the user."

class LiteRtLlmEngine : LocalLlmEngine {

    private var engine: Engine? = null
    private var chatConversation: Conversation? = null
    private val sessionLock = Any()
    override var isReady: Boolean = false
        private set

    override suspend fun initialize(modelPath: String): Unit = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            chatConversation?.close()
            chatConversation = null
            engine?.close()
            engine = null
            isReady = false

            AppLog.d(TAG, "Loading model from $modelPath")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
            val loadedEngine = Engine(config)
            loadedEngine.initialize()
            engine = loadedEngine
            chatConversation = createConversation(loadedEngine)
            isReady = true
            AppLog.d(TAG, "Engine ready")
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            val loadedEngine = checkNotNull(engine) { "Engine not initialized" }
            val previousConversation = chatConversation
            previousConversation?.close()
            chatConversation = null

            try {
                loadedEngine.createConversation(createConversationConfig()).use { conversation ->
                    renderResponse(conversation, prompt)
                }
            } finally {
                if (engine === loadedEngine && isReady) {
                    chatConversation = createConversation(loadedEngine)
                }
            }
        }
    }

    override suspend fun sendChatMessage(message: String): String = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            val loadedEngine = checkNotNull(engine) { "Engine not initialized" }
            val conversation = chatConversation ?: createConversation(loadedEngine).also {
                chatConversation = it
            }
            renderResponse(conversation, message)
        }
    }

    override fun resetChat() {
        synchronized(sessionLock) {
            val loadedEngine = engine ?: return
            chatConversation?.close()
            chatConversation = createConversation(loadedEngine)
        }
    }

    override fun close() {
        synchronized(sessionLock) {
            chatConversation?.close()
            chatConversation = null
            engine?.close()
            engine = null
            isReady = false
        }
    }

    private fun createConversation(engine: Engine): Conversation {
        return engine.createConversation(createConversationConfig())
    }

    private fun createConversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(DEFAULT_SYSTEM_PROMPT)
        )
    }

    private fun renderResponse(conversation: Conversation, prompt: String): String {
        val message = conversation.sendMessage(Contents.of(prompt), emptyMap())
        return sanitizeResponse(message.contents.toString())
    }

    private fun sanitizeResponse(raw: String): String {
        return raw
            .replace("<turn>model", "")
            .replace("<turn>user", "")
            .replace("<turn>", "")
            .trim()
    }
}
