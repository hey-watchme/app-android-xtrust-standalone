package com.xtrust.standalone.ui

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtrust.standalone.audio.MicrophoneVadMonitor
import com.xtrust.standalone.audio.WavFileWriter
import com.xtrust.standalone.data.TopicEntity
import com.xtrust.standalone.data.TranscriptRepository
import com.xtrust.standalone.llm.LiteRtGemmaEngine
import com.xtrust.standalone.llm.LocalLlmEngine
import com.xtrust.standalone.vad.EnergyVadEngine
import com.xtrust.standalone.vad.VadFrameResult
import kotlinx.coroutines.Dispatchers
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
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class UiState(
    val llmReady: Boolean = false,
    val llmModelPath: String = "",
    val isProcessing: Boolean = false,
    val lastError: String? = null,
    val memorySnapshot: MemorySnapshot = MemorySnapshot(),
    val vadDebugState: VadDebugState = VadDebugState()
)

class XtrustViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository()
    private val llmEngine: LocalLlmEngine = LiteRtGemmaEngine()
    private val vadEngine = EnergyVadEngine()
    private val microphoneVadMonitor = MicrophoneVadMonitor(vadEngine)

    private val defaultModelPath: String = run {
        val dir = application.getExternalFilesDir(null)
            ?: application.filesDir
        File(dir, "models").mkdirs()  // app が owner になるので chmod 不要
        File(dir, "models/gemma-4-E2B-it.litertlm").absolutePath
    }
    private val segmentOutputDir: File = run {
        val dir = application.getExternalFilesDir(null) ?: application.filesDir
        File(dir, "audio-segments").apply { mkdirs() }
    }

    private val _uiState = MutableStateFlow(UiState(llmModelPath = defaultModelPath))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = repository.topics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var nextChatMessageId = 1L
    private var nextSegmentId = 1L
    private val captureLock = Any()
    private val preRollFrames = ArrayDeque<ShortArray>()
    private val activeSpeechFrames = mutableListOf<ShortArray>()
    private val maxPreRollFrames = 10

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

    fun startVadMonitoring() {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        lastError = null,
                        vadDebugState = it.vadDebugState.copy(
                            isListening = true,
                            isSpeechDetected = false,
                            rmsDb = -120.0
                        )
                    )
                }
                microphoneVadMonitor.start(viewModelScope) { frame, result ->
                    processVadFrame(frame, result)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastError = "VAD start failed: ${e.message}",
                        vadDebugState = it.vadDebugState.copy(isListening = false, isSpeechDetected = false)
                    )
                }
            }
        }
    }

    fun stopVadMonitoring() {
        viewModelScope.launch {
            microphoneVadMonitor.stop()
            flushActiveSpeechSegment()
            _uiState.update {
                it.copy(
                    vadDebugState = it.vadDebugState.copy(
                        isListening = false,
                        isSpeechDetected = false
                    )
                )
            }
        }
    }

    fun reportAudioPermissionDenied() {
        _uiState.update { it.copy(lastError = "マイク権限が必要です。録音許可を有効にしてください。") }
    }

    fun clearSavedSegments() {
        segmentOutputDir.listFiles()?.forEach { it.delete() }
        synchronized(captureLock) {
            preRollFrames.clear()
            activeSpeechFrames.clear()
        }
        _uiState.update {
            it.copy(
                vadDebugState = it.vadDebugState.copy(
                    detectedSegments = 0,
                    lastSpeechDurationMs = 0,
                    savedSegments = emptyList()
                )
            )
        }
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

    private fun processVadFrame(frame: ShortArray, result: VadFrameResult) {
        var segmentToSave: ShortArray? = null
        var segmentDurationMs = 0L

        synchronized(captureLock) {
            preRollFrames.addLast(frame.copyOf())
            while (preRollFrames.size > maxPreRollFrames) {
                preRollFrames.removeFirst()
            }

            if (result.speechStarted && activeSpeechFrames.isEmpty()) {
                activeSpeechFrames.addAll(preRollFrames.map { it.copyOf() })
            }

            if (result.speechStarted || result.isSpeechDetected || activeSpeechFrames.isNotEmpty()) {
                activeSpeechFrames.add(frame.copyOf())
            }

            if (result.speechEnded && activeSpeechFrames.isNotEmpty()) {
                segmentToSave = flattenFrames(activeSpeechFrames)
                segmentDurationMs = calculateDurationMs(segmentToSave!!.size)
                activeSpeechFrames.clear()
                preRollFrames.clear()
            }
        }

        _uiState.update { current ->
            current.copy(
                vadDebugState = current.vadDebugState.copy(
                    isListening = true,
                    isSpeechDetected = result.isSpeechDetected,
                    rmsDb = result.rmsDb,
                    detectedSegments = current.vadDebugState.detectedSegments +
                        if (result.speechEnded) 1 else 0,
                    lastSpeechDurationMs = if (result.speechEnded || result.isSpeechDetected) {
                        result.speechDurationMs
                    } else {
                        current.vadDebugState.lastSpeechDurationMs
                    }
                )
            )
        }

        if (segmentToSave != null) {
            saveSegment(segmentToSave!!, segmentDurationMs)
        }
    }

    private fun flushActiveSpeechSegment() {
        val segmentToSave: ShortArray?
        synchronized(captureLock) {
            segmentToSave = if (activeSpeechFrames.isNotEmpty()) {
                flattenFrames(activeSpeechFrames)
            } else {
                null
            }
            activeSpeechFrames.clear()
            preRollFrames.clear()
        }
        if (segmentToSave != null && segmentToSave.isNotEmpty()) {
            saveSegment(segmentToSave, calculateDurationMs(segmentToSave.size))
        }
    }

    private fun saveSegment(samples: ShortArray, durationMs: Long) {
        if (samples.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = buildSegmentFileName()
            val outputFile = File(segmentOutputDir, fileName)
            try {
                WavFileWriter.writeMono16BitPcm(outputFile, samples, SAMPLE_RATE)
                val segment = AudioSegment(
                    id = nextSegmentId++,
                    fileName = fileName,
                    filePath = outputFile.absolutePath,
                    durationMs = durationMs,
                    sizeBytes = outputFile.length()
                )
                _uiState.update { current ->
                    current.copy(
                        vadDebugState = current.vadDebugState.copy(
                            savedSegments = (listOf(segment) + current.vadDebugState.savedSegments).take(12)
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "セグメント保存失敗: ${e.message}") }
            }
        }
    }

    private fun buildSegmentFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return "segment-$timestamp.wav"
    }

    private fun flattenFrames(frames: List<ShortArray>): ShortArray {
        val totalSize = frames.sumOf { it.size }
        val flattened = ShortArray(totalSize)
        var offset = 0
        for (frame in frames) {
            frame.copyInto(flattened, destinationOffset = offset)
            offset += frame.size
        }
        return flattened
    }

    private fun calculateDurationMs(sampleCount: Int): Long {
        return sampleCount * 1000L / SAMPLE_RATE
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            microphoneVadMonitor.stop()
        }
        llmEngine.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
