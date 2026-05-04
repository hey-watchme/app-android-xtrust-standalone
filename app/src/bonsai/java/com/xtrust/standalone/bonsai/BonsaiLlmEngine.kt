package com.xtrust.standalone.bonsai

import android.app.Application
import com.xtrust.bonsai.runtime.BonsaiNativeBridge
import com.xtrust.standalone.llm.LlmDiagnostics
import com.xtrust.standalone.llm.LocalLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_SYSTEM_PROMPT =
    "You are a concise assistant. Reply in the same language as the user. " +
        "Do not output <think> tags, hidden reasoning, or XML-style control tags. " +
        "Answer directly and briefly."
private const val DEFAULT_GENERATE_TOKENS = 320

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
        sanitizeAssistantResponse(bridge.complete(prompt, DEFAULT_GENERATE_TOKENS))
    }

    override suspend fun sendChatMessage(message: String): String = withContext(Dispatchers.IO) {
        val bridge = checkNotNull(runtime) { "Bonsai runtime not initialized" }
        sanitizeAssistantResponse(bridge.complete(message, DEFAULT_GENERATE_TOKENS))
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

    private fun sanitizeAssistantResponse(raw: String): String {
        val strippedThinkBlocks = THINK_BLOCK_REGEX.replace(raw, "")
        val strippedTags = THINK_TAG_REGEX.replace(strippedThinkBlocks, "")
        return strippedTags.trim().ifEmpty { raw.trim() }
    }

    private companion object {
        val THINK_BLOCK_REGEX = Regex(
            pattern = "<think\\b[^>]*>.*?</think>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val THINK_TAG_REGEX = Regex("</?think\\b[^>]*>", RegexOption.IGNORE_CASE)
    }
}
