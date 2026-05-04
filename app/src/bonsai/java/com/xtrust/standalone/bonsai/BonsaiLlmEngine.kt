package com.xtrust.standalone.bonsai

import android.app.Application
import com.xtrust.bonsai.runtime.BonsaiNativeBridge
import com.xtrust.standalone.llm.LlmDiagnostics
import com.xtrust.standalone.llm.LocalLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_SYSTEM_PROMPT =
    "You are a concise assistant. Reply in the same language as the user."
private const val DEFAULT_GENERATE_TOKENS = 256

class BonsaiLlmEngine(
    private val application: Application
) : LocalLlmEngine, LlmDiagnostics {

    private var runtime: BonsaiNativeBridge? = null
    private var loadedModelPath: String? = null

    override var isReady: Boolean = false
        private set

    override suspend fun initialize(modelPath: String): Unit = withContext(Dispatchers.IO) {
        val bridge = runtime ?: BonsaiNativeBridge(application).also { runtime = it }
        if (!isReady || loadedModelPath != modelPath) {
            bridge.loadModel(modelPath, DEFAULT_SYSTEM_PROMPT)
            loadedModelPath = modelPath
        } else {
            bridge.resetConversation(DEFAULT_SYSTEM_PROMPT)
        }
        isReady = true
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val bridge = checkNotNull(runtime) { "Bonsai runtime not initialized" }
        bridge.resetConversation(DEFAULT_SYSTEM_PROMPT)
        bridge.complete(prompt, DEFAULT_GENERATE_TOKENS)
    }

    override suspend fun sendChatMessage(message: String): String = withContext(Dispatchers.IO) {
        val bridge = checkNotNull(runtime) { "Bonsai runtime not initialized" }
        bridge.complete(message, DEFAULT_GENERATE_TOKENS)
    }

    override fun resetChat() {
        runtime?.resetConversation(DEFAULT_SYSTEM_PROMPT)
    }

    override fun close() {
        runtime?.close()
        runtime = null
        loadedModelPath = null
        isReady = false
    }

    override suspend fun runtimeInfo(): String = withContext(Dispatchers.IO) {
        val bridge = checkNotNull(runtime) { "Bonsai runtime not initialized" }
        bridge.systemInfoText()
    }

    override suspend fun benchmark(): String = withContext(Dispatchers.IO) {
        val bridge = checkNotNull(runtime) { "Bonsai runtime not initialized" }
        bridge.bench(256, 128, 1, 1)
    }
}
