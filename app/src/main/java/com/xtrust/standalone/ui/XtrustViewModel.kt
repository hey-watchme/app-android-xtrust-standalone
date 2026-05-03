package com.xtrust.standalone.ui

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtrust.standalone.data.TopicEntity
import com.xtrust.standalone.data.TranscriptRepository
import com.xtrust.standalone.llm.LiteRtGemmaEngine
import com.xtrust.standalone.llm.LocalLlmEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val llmReady: Boolean = false,
    val llmModelPath: String = "",
    val isProcessing: Boolean = false,
    val lastError: String? = null,
    val memorySnapshot: MemorySnapshot = MemorySnapshot()
)

class XtrustViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository()
    private val llmEngine: LocalLlmEngine = LiteRtGemmaEngine()

    private val defaultModelPath: String = run {
        val dir = application.getExternalFilesDir(null)
            ?: application.filesDir
        File(dir, "models").mkdirs()  // app が owner になるので chmod 不要
        File(dir, "models/gemma-4-E2B-it.litertlm").absolutePath
    }

    private val _uiState = MutableStateFlow(UiState(llmModelPath = defaultModelPath))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = repository.topics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var nextChatMessageId = 1L

    init {
        startMemoryMonitor()
        autoLoadLlmModel()
    }

    fun loadLlmModel(modelPath: String = defaultModelPath) {
        loadLlmModelInternal(modelPath, showMissingErrors = true)
    }

    private fun autoLoadLlmModel() {
        val file = File(defaultModelPath)
        if (!file.exists() || file.length() < 1_000_000_000L) {
            return
        }
        loadLlmModelInternal(defaultModelPath, showMissingErrors = false)
    }

    private fun loadLlmModelInternal(modelPath: String, showMissingErrors: Boolean) {
        if (_uiState.value.isProcessing) return
        if (_uiState.value.llmReady && _uiState.value.llmModelPath == modelPath) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, lastError = null) }
            val file = File(modelPath)
            Log.d("XtrustVM", "checking path: $modelPath  exists=${file.exists()}  size=${file.length()}")
            when {
                !file.exists() ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            lastError = if (showMissingErrors) {
                                "モデルファイルが見つかりません。管理者に配置を依頼してください。"
                            } else {
                                null
                            }
                        )
                    }
                file.length() < 1_000_000_000L ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            lastError = if (showMissingErrors) {
                                "モデルファイルが不完全です（${file.length() / 1_000_000}MB）。転送が完了してから再試行してください。"
                            } else {
                                null
                            }
                        )
                    }
                else -> try {
                    llmEngine.initialize(modelPath)
                    _chatMessages.value = emptyList()
                    _uiState.update {
                        it.copy(
                            llmReady = true,
                            llmModelPath = modelPath,
                            isProcessing = false
                        )
                    }
                    refreshMemorySnapshot()
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            llmReady = false,
                            isProcessing = false,
                            lastError = "ロード失敗: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun summarizeTranscript(transcript: String) {
        if (!llmEngine.isReady) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, lastError = null) }
            try {
                val prompt = """
                    Read the conversation below and respond in the same language.
                    First line: a short title only.
                    Second part: one concise paragraph summary only.
                    
                    Conversation:
                    $transcript
                """.trimIndent()
                val result = llmEngine.generate(prompt)
                val topic = TopicEntity(
                    title = result.lines().firstOrNull() ?: "Topic",
                    summary = result,
                    startAt = System.currentTimeMillis(),
                    endAt = System.currentTimeMillis(),
                    llmProvider = "litert-lm",
                    llmModel = "gemma4-e2b"
                )
                repository.saveTopic(topic)
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
                refreshMemorySnapshot()
            }
        }
    }

    fun sendChatMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        if (!llmEngine.isReady) {
            _uiState.update { it.copy(lastError = "Gemma 4 E2B が未ロードです。Settings で確認してください。") }
            return
        }

        appendChatMessage(ChatRole.User, trimmed)
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, lastError = null) }
            try {
                val reply = llmEngine.sendChatMessage(trimmed)
                appendChatMessage(ChatRole.Assistant, reply)
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "チャット失敗: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
                refreshMemorySnapshot()
            }
        }
    }

    fun resetChat() {
        llmEngine.resetChat()
        _chatMessages.value = emptyList()
        _uiState.update { it.copy(lastError = null) }
        refreshMemorySnapshot()
    }

    private fun appendChatMessage(role: ChatRole, text: String) {
        _chatMessages.update { current ->
            current + ChatMessage(
                id = nextChatMessageId++,
                role = role,
                text = text
            )
        }
    }

    private fun startMemoryMonitor() {
        viewModelScope.launch {
            while (isActive) {
                refreshMemorySnapshot()
                delay(1500)
            }
        }
    }

    private fun refreshMemorySnapshot() {
        val activityManager = getApplication<Application>()
            .getSystemService(ActivityManager::class.java)
            ?: return
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val runtime = Runtime.getRuntime()

        _uiState.update {
            it.copy(
                memorySnapshot = MemorySnapshot(
                    deviceTotalMb = bytesToMb(memoryInfo.totalMem),
                    deviceUsedMb = bytesToMb(memoryInfo.totalMem - memoryInfo.availMem),
                    deviceAvailableMb = bytesToMb(memoryInfo.availMem),
                    appHeapUsedMb = bytesToMb(runtime.totalMemory() - runtime.freeMemory()),
                    appHeapMaxMb = bytesToMb(runtime.maxMemory()),
                    nativeHeapMb = bytesToMb(Debug.getNativeHeapAllocatedSize()),
                    lowMemory = memoryInfo.lowMemory
                )
            )
        }
    }

    private fun bytesToMb(bytes: Long): Long = bytes / (1024 * 1024)

    override fun onCleared() {
        super.onCleared()
        llmEngine.close()
    }
}
