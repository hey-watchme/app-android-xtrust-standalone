package com.xtrust.standalone.ui

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtrust.standalone.audio.MicrophoneVadMonitor
import com.xtrust.standalone.audio.WavFileWriter
import com.xtrust.standalone.data.CardEntity
import com.xtrust.standalone.asr.LocalAsrEngine
import com.xtrust.standalone.asr.SherpaOnnxAsrEngine
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val vadDebugState: VadDebugState = VadDebugState(),
    val asrDebugState: AsrDebugState = AsrDebugState()
)

class XtrustViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository(application.applicationContext)
    private val llmEngine: LocalLlmEngine = LiteRtGemmaEngine()
    private val asrEngine: LocalAsrEngine = SherpaOnnxAsrEngine()
    private val vadEngine = EnergyVadEngine()
    private val microphoneVadMonitor = MicrophoneVadMonitor(vadEngine)

    private val defaultModelPath: String = run {
        val dir = application.getExternalFilesDir(null)
            ?: application.filesDir
        File(dir, "models").mkdirs()  // app が owner になるので chmod 不要
        File(dir, "models/gemma-4-E2B-it.litertlm").absolutePath
    }
    private val appExternalDir: File = application.getExternalFilesDir(null) ?: application.filesDir
    private val segmentOutputDir: File = run {
        File(appExternalDir, "audio-segments").apply { mkdirs() }
    }
    private val asrBaseDir: File = File(appExternalDir, "asr").apply { mkdirs() }

    private val _uiState = MutableStateFlow(
        UiState(
            llmModelPath = defaultModelPath,
            asrDebugState = AsrDebugState(modelDirPath = resolveAsrModelDirPath())
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = repository.topics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var nextChatMessageId = 1L
    private val captureLock = Any()
    private val asrTranscriptionMutex = Mutex()
    private val preRollFrames = ArrayDeque<ShortArray>()
    private val activeSpeechFrames = mutableListOf<ShortArray>()
    private val maxPreRollFrames = 10
    private var activeSessionId: Long? = null

    init {
        ensureKnownAsrDirs()
        viewModelScope.launch {
            repository.recoverDanglingSessions(System.currentTimeMillis())
            repository.refresh()
        }
        startMemoryMonitor()
        autoLoadLlmModel()
        autoLoadAsrModel()
    }

    fun loadLlmModel(modelPath: String = defaultModelPath) {
        loadLlmModelInternal(modelPath, showMissingErrors = true)
    }

    fun loadAsrModel(modelDirPath: String = resolveAsrModelDirPath()) {
        loadAsrModelInternal(modelDirPath, showMissingErrors = true)
    }

    private fun autoLoadLlmModel() {
        val file = File(defaultModelPath)
        if (!file.exists() || file.length() < 1_000_000_000L) {
            return
        }
        loadLlmModelInternal(defaultModelPath, showMissingErrors = false)
    }

    private fun autoLoadAsrModel() {
        val modelDirPath = resolveAsrModelDirPath()
        val modelDir = File(modelDirPath)
        if (!hasAsrModelFiles(modelDir)) {
            updateAsrAccessSummary(modelDir)
            return
        }
        loadAsrModelInternal(modelDirPath, showMissingErrors = false)
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

    private fun loadAsrModelInternal(modelDirPath: String, showMissingErrors: Boolean) {
        if (_uiState.value.asrDebugState.isLoadingModel) return
        if (_uiState.value.asrDebugState.isReady &&
            _uiState.value.asrDebugState.modelDirPath == modelDirPath
        ) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lastError = null,
                    asrDebugState = it.asrDebugState.copy(
                        modelDirPath = modelDirPath,
                        isLoadingModel = true
                    )
                )
            }

            val modelDir = File(modelDirPath)
            val modelFile = File(modelDir, SherpaOnnxAsrEngine.MODEL_FILE_NAME)
            val tokensFile = File(modelDir, SherpaOnnxAsrEngine.TOKENS_FILE_NAME)

            when {
                !modelFile.exists() || !tokensFile.exists() -> {
                    val accessSummary = buildAsrAccessSummary(modelDir)
                    _uiState.update {
                        it.copy(
                            lastError = if (showMissingErrors) {
                                "ASR model files not found or not readable. Place ${SherpaOnnxAsrEngine.MODEL_FILE_NAME} and ${SherpaOnnxAsrEngine.TOKENS_FILE_NAME} under $modelDirPath and ensure the directory is app-readable."
                            } else {
                                null
                            },
                            asrDebugState = it.asrDebugState.copy(
                                isReady = false,
                                isLoadingModel = false,
                                modelDirPath = modelDirPath,
                                modelAccessSummary = accessSummary
                            )
                        )
                    }
                }
                else -> {
                    try {
                        asrEngine.initialize(modelDirPath)
                        _uiState.update {
                            it.copy(
                                asrDebugState = it.asrDebugState.copy(
                                    isReady = true,
                                    isLoadingModel = false,
                                    modelDirPath = modelDirPath,
                                    modelAccessSummary = buildAsrAccessSummary(modelDir)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                lastError = "ASR load failed: ${e.message}",
                                asrDebugState = it.asrDebugState.copy(
                                    isReady = false,
                                    isLoadingModel = false,
                                    modelDirPath = modelDirPath,
                                    modelAccessSummary = buildAsrAccessSummary(modelDir)
                                )
                            )
                        }
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
                    sessionId = currentOrLatestSessionId(),
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
            if (_uiState.value.vadDebugState.isListening || activeSessionId != null) return@launch

            var sessionId: Long? = null
            try {
                sessionId = repository.startSession(System.currentTimeMillis())
                activeSessionId = sessionId
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
                microphoneVadMonitor.start(
                    scope = viewModelScope,
                    onFrame = { frame, result ->
                        processVadFrame(frame, result)
                    },
                    onError = { throwable ->
                        handleMonitoringFailure(throwable)
                    }
                )
            } catch (e: Exception) {
                val failedSessionId = sessionId
                if (failedSessionId != null) {
                    repository.discardSession(failedSessionId)
                }
                activeSessionId = null
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
            completeActiveSession()
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

    private suspend fun completeActiveSession() {
        val sessionId = activeSessionId
        activeSessionId = null

        microphoneVadMonitor.stop()
        flushActiveSpeechSegment()

        if (sessionId != null) {
            repository.completeOrDeleteEmptySession(sessionId, System.currentTimeMillis())
        }
    }

    private fun handleMonitoringFailure(throwable: Throwable) {
        viewModelScope.launch {
            failActiveSession("録音処理エラー: ${throwable.message ?: throwable.javaClass.simpleName}")
            _uiState.update {
                it.copy(
                    lastError = "VAD monitor failed: ${throwable.message}",
                    vadDebugState = it.vadDebugState.copy(
                        isListening = false,
                        isSpeechDetected = false
                    )
                )
            }
        }
    }

    private suspend fun failActiveSession(errorMessage: String) {
        val sessionId = activeSessionId
        activeSessionId = null

        microphoneVadMonitor.stop()
        flushActiveSpeechSegment()

        if (sessionId != null) {
            repository.failSession(
                sessionId = sessionId,
                endedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        }
    }

    fun reportAudioPermissionDenied() {
        _uiState.update { it.copy(lastError = "マイク権限が必要です。録音許可を有効にしてください。") }
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
        var shouldPersistSegment = false

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
                shouldPersistSegment = segmentDurationMs >= MIN_SEGMENT_DURATION_MS
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
                        if (shouldPersistSegment) 1 else 0,
                    lastSpeechDurationMs = if (result.speechEnded || result.isSpeechDetected) {
                        result.speechDurationMs
                    } else {
                        current.vadDebugState.lastSpeechDurationMs
                    }
                )
            )
        }

        if (segmentToSave != null && shouldPersistSegment) {
            saveSegment(
                samples = segmentToSave!!,
                durationMs = segmentDurationMs,
                sessionId = activeSessionId
            )
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
            val durationMs = calculateDurationMs(segmentToSave.size)
            if (durationMs >= MIN_SEGMENT_DURATION_MS) {
                _uiState.update { current ->
                    current.copy(
                        vadDebugState = current.vadDebugState.copy(
                            detectedSegments = current.vadDebugState.detectedSegments + 1,
                            lastSpeechDurationMs = durationMs
                        )
                    )
                }
                saveSegment(
                    samples = segmentToSave,
                    durationMs = durationMs,
                    sessionId = activeSessionId
                )
            }
        }
    }

    private fun saveSegment(samples: ShortArray, durationMs: Long, sessionId: Long?) {
        if (samples.isEmpty()) return
        if (sessionId == null) {
            _uiState.update { it.copy(lastError = "録音セッションが未開始のため、セグメントを保存できませんでした。") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = buildSegmentFileName()
            val outputFile = File(segmentOutputDir, fileName)
            try {
                WavFileWriter.writeMono16BitPcm(outputFile, samples, SAMPLE_RATE)
                val cardId = repository.saveCard(
                    CardEntity(
                        sessionId = sessionId,
                        audioPath = outputFile.absolutePath,
                        transcript = null,
                        asrProvider = "sherpa-onnx",
                        asrModel = "sensevoice-int8-ja",
                        durationMs = durationMs,
                        sizeBytes = outputFile.length(),
                        recordedAt = System.currentTimeMillis()
                    )
                )
                val segment = AudioSegment(
                    id = cardId,
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
                if (AUTO_TRANSCRIBE_SEGMENTS) {
                    viewModelScope.launch {
                        updateSegment(segment.id) { currentSegment ->
                            currentSegment.copy(isTranscribing = true, asrError = null)
                        }
                        transcribeSegmentInternal(segment.id, showErrors = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "セグメント保存失敗: ${e.message}") }
            }
        }
    }

    private suspend fun transcribeSegmentInternal(segmentId: Long, showErrors: Boolean) {
        asrTranscriptionMutex.withLock {
            val segment = _uiState.value.vadDebugState.savedSegments.firstOrNull { it.id == segmentId }
            if (segment == null) return

            if (!ensureAsrReady(showErrors = showErrors)) {
                updateSegment(segmentId) { it.copy(isTranscribing = false, asrError = "ASR model is not loaded") }
                return
            }

            val startedAt = System.currentTimeMillis()
            try {
                val transcript = asrEngine.transcribe(segment.filePath)
                val elapsedMs = System.currentTimeMillis() - startedAt
                val audioDurationMs = transcript.sampleCount * 1000L / transcript.sampleRate
                val rtf = if (audioDurationMs > 0) {
                    elapsedMs.toDouble() / audioDurationMs.toDouble()
                } else {
                    0.0
                }

                repository.updateCardTranscription(
                    cardId = segmentId,
                    transcript = transcript.text.ifBlank { "[no text]" },
                    transcriptionMs = elapsedMs,
                    realTimeFactor = rtf
                )
                updateSegment(segmentId) {
                    it.copy(
                        transcript = transcript.text.ifBlank { "[no text]" },
                        isTranscribing = false,
                        asrError = null,
                        transcriptionMs = elapsedMs,
                        realTimeFactor = rtf
                    )
                }
                _uiState.update {
                    it.copy(
                        asrDebugState = it.asrDebugState.copy(
                            lastTranscriptionMs = elapsedMs,
                            lastRealTimeFactor = rtf
                        )
                    )
                }
                refreshMemorySnapshot()
            } catch (e: Exception) {
                updateSegment(segmentId) {
                    it.copy(
                        isTranscribing = false,
                        asrError = e.message ?: "Unknown ASR error"
                    )
                }
                if (showErrors) {
                    _uiState.update { it.copy(lastError = "ASR transcription failed: ${e.message}") }
                }
            }
        }
    }

    private suspend fun ensureAsrReady(showErrors: Boolean): Boolean {
        if (asrEngine.isReady) return true

        val modelDir = resolveAsrModelDirPath(
            preferredPath = _uiState.value.asrDebugState.modelDirPath
        )
        if (!hasAsrModelFiles(File(modelDir))) {
            if (showErrors) {
                _uiState.update {
                    it.copy(
                        lastError = "ASR model files are missing or unreadable. Push model.int8.onnx and tokens.txt to $modelDir and check directory permissions.",
                        asrDebugState = it.asrDebugState.copy(
                            modelDirPath = modelDir,
                            modelAccessSummary = buildAsrAccessSummary(File(modelDir))
                        )
                    )
                }
            }
            return false
        }

        return try {
            asrEngine.initialize(modelDir)
            _uiState.update {
                it.copy(
                    asrDebugState = it.asrDebugState.copy(
                        isReady = true,
                        isLoadingModel = false,
                        modelDirPath = modelDir,
                        modelAccessSummary = buildAsrAccessSummary(File(modelDir))
                    )
                )
            }
            true
        } catch (e: Exception) {
            if (showErrors) {
                _uiState.update {
                    it.copy(
                        lastError = "ASR load failed: ${e.message}",
                        asrDebugState = it.asrDebugState.copy(
                            modelDirPath = modelDir,
                            modelAccessSummary = buildAsrAccessSummary(File(modelDir))
                        )
                    )
                }
            }
            false
        }
    }

    private fun updateSegment(segmentId: Long, transform: (AudioSegment) -> AudioSegment) {
        _uiState.update { current ->
            current.copy(
                vadDebugState = current.vadDebugState.copy(
                    savedSegments = current.vadDebugState.savedSegments.map { segment ->
                        if (segment.id == segmentId) transform(segment) else segment
                    }
                )
            )
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

    private fun currentOrLatestSessionId(): Long? {
        return activeSessionId ?: sessions.value.firstOrNull()?.session?.id
    }

    private fun resolveAsrModelDirPath(preferredPath: String? = null): String {
        val candidateDirs = buildList {
            preferredPath
                ?.takeIf { it.isNotBlank() }
                ?.let { add(File(it)) }
            KNOWN_ASR_MODEL_DIR_NAMES.forEach { add(File(asrBaseDir, it)) }
            asrBaseDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { add(it) }
        }

        val existingModelDir = candidateDirs.firstOrNull(::hasAsrModelFiles)
        if (existingModelDir != null) {
            return existingModelDir.absolutePath
        }

        return File(asrBaseDir, KNOWN_ASR_MODEL_DIR_NAMES.first()).apply { mkdirs() }.absolutePath
    }

    private fun hasAsrModelFiles(dir: File): Boolean {
        return File(dir, SherpaOnnxAsrEngine.MODEL_FILE_NAME).exists() &&
            File(dir, SherpaOnnxAsrEngine.TOKENS_FILE_NAME).exists()
    }

    private fun ensureKnownAsrDirs() {
        KNOWN_ASR_MODEL_DIR_NAMES.forEach { dirName ->
            File(asrBaseDir, dirName).mkdirs()
        }
    }

    private fun updateAsrAccessSummary(modelDir: File) {
        _uiState.update {
            it.copy(
                asrDebugState = it.asrDebugState.copy(
                    modelDirPath = modelDir.absolutePath,
                    modelAccessSummary = buildAsrAccessSummary(modelDir)
                )
            )
        }
    }

    private fun buildAsrAccessSummary(modelDir: File): String {
        val modelFile = File(modelDir, SherpaOnnxAsrEngine.MODEL_FILE_NAME)
        val tokensFile = File(modelDir, SherpaOnnxAsrEngine.TOKENS_FILE_NAME)
        return buildString {
            append("dir exists=${modelDir.exists()} read=${modelDir.canRead()} exec=${modelDir.canExecute()}")
            append(" | model exists=${modelFile.exists()} read=${modelFile.canRead()} size=${modelFile.length()}")
            append(" | tokens exists=${tokensFile.exists()} read=${tokensFile.canRead()} size=${tokensFile.length()}")
        }
    }

    override fun onCleared() {
        runBlocking {
            completeActiveSession()
        }
        super.onCleared()
        asrEngine.close()
        llmEngine.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SEGMENT_DURATION_MS = 900L
        const val AUTO_TRANSCRIBE_SEGMENTS = true
        val KNOWN_ASR_MODEL_DIR_NAMES = listOf(
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        )
    }
}
