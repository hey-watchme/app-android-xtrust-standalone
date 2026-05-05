package com.xtrust.standalone.ui

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtrust.standalone.XtrustApplication
import com.xtrust.standalone.audio.MicrophoneVadMonitor
import com.xtrust.standalone.audio.WavFileWriter
import com.xtrust.standalone.data.CardEntity
import com.xtrust.standalone.data.ChatThreadEntity
import com.xtrust.standalone.asr.LocalAsrEngine
import com.xtrust.standalone.asr.SherpaOnnxAsrEngine
import com.xtrust.standalone.data.ChatMessageEntity as StoredChatMessageEntity
import com.xtrust.standalone.data.TopicEntity
import com.xtrust.standalone.data.TranscriptRepository
import com.xtrust.standalone.data.WrapupJobEntity
import com.xtrust.standalone.llm.LlmDiagnostics
import com.xtrust.standalone.llm.LocalLlmCatalog
import com.xtrust.standalone.llm.LocalLlmDefinition
import com.xtrust.standalone.llm.LocalLlmEngine
import com.xtrust.standalone.vad.EnergyVadEngine
import com.xtrust.standalone.vad.VadFrameResult
import com.xtrust.standalone.wrapup.SessionWrapupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val isLoadingLlmModel: Boolean = false,
    val llmModelPath: String = "",
    val selectedLlmId: String = "",
    val loadedLlmId: String? = null,
    val selectedChatThreadId: Long? = null,
    val chatThreads: List<ChatThreadItem> = emptyList(),
    val availableLlmOptions: List<LlmModelOption> = emptyList(),
    val isProcessing: Boolean = false,
    val lastError: String? = null,
    val wrapupJobBySession: Map<Long, WrapupJobEntity> = emptyMap(),
    val llmRuntimeInfo: String = "",
    val llmBenchmarkResult: String? = null,
    val isRunningBenchmark: Boolean = false,
    val memorySnapshot: MemorySnapshot = MemorySnapshot(),
    val vadDebugState: VadDebugState = VadDebugState(),
    val asrDebugState: AsrDebugState = AsrDebugState()
)

class XtrustViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository(application.applicationContext)
    private val app get() = getApplication<XtrustApplication>()
    private var llmEngine: LocalLlmEngine?
        get() = app.llmEngine
        set(value) { app.llmEngine = value }
    private var wrapupPollingJob: Job? = null
    private val asrEngine: LocalAsrEngine = SherpaOnnxAsrEngine()
    private val vadEngine = EnergyVadEngine()
    private val microphoneVadMonitor = MicrophoneVadMonitor(vadEngine)

    private val modelBaseDir: File = run {
        val dir = application.getExternalFilesDir(null)
            ?: application.filesDir
        File(dir, "models").apply { mkdirs() } // app が owner になるので chmod 不要
    }
    private val llmDefinitions = LocalLlmCatalog.create(modelBaseDir)
    private val defaultLlmDefinition = llmDefinitions.first { it.id == LocalLlmCatalog.DEFAULT_MODEL_ID }
    private val appExternalDir: File = application.getExternalFilesDir(null) ?: application.filesDir
    private val segmentOutputDir: File = run {
        File(appExternalDir, "audio-segments").apply { mkdirs() }
    }
    private val asrBaseDir: File = File(appExternalDir, "asr").apply { mkdirs() }

    private val _uiState = MutableStateFlow(
        UiState(
            llmModelPath = defaultLlmDefinition.modelPath,
            selectedLlmId = defaultLlmDefinition.id,
            availableLlmOptions = llmDefinitions.map(::toLlmModelOption),
            asrDebugState = AsrDebugState(modelDirPath = resolveAsrModelDirPath())
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = repository.topics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val captureLock = Any()
    private val asrTranscriptionMutex = Mutex()
    private val preRollFrames = ArrayDeque<ShortArray>()
    private val activeSpeechFrames = mutableListOf<ShortArray>()
    private val maxPreRollFrames = 10
    private var activeSessionId: Long? = null
    private var activeRuntimeThreadId: Long? = null
    private var runtimeNeedsBootstrap: Boolean = true

    init {
        ensureKnownAsrDirs()
        viewModelScope.launch {
            repository.recoverDanglingSessions(System.currentTimeMillis())
            repository.refresh()
            ensureChatThreadSelection()
            resumePendingWrapupJobs()
        }
        startMemoryMonitor()
        autoLoadLlmModel()
        autoLoadAsrModel()
    }

    fun loadLlmModel() {
        loadLlmModelInternal(
            definition = selectedLlmDefinition() ?: return,
            showMissingErrors = true
        )
    }

    fun loadAsrModel(modelDirPath: String = resolveAsrModelDirPath()) {
        loadAsrModelInternal(modelDirPath, showMissingErrors = true)
    }

    private fun autoLoadLlmModel() {
        val candidate = llmDefinitions.firstOrNull { definition ->
            definition.isLoadable && hasCompleteLlmModelFile(definition)
        } ?: return

        _uiState.update {
            it.copy(
                selectedLlmId = candidate.id,
                llmModelPath = candidate.modelPath
            )
        }
        loadLlmModelInternal(candidate, showMissingErrors = false)
    }

    fun selectLlmModel(modelId: String) {
        val definition = llmDefinitions.firstOrNull { it.id == modelId } ?: return
        _uiState.update {
            it.copy(
                selectedLlmId = definition.id,
                llmModelPath = definition.modelPath,
                lastError = null,
                llmBenchmarkResult = null
            )
        }
    }

    fun releaseLlmModel() {
        releaseCurrentLlmEngine()
        clearLlmLoadedState(clearChat = false, clearLastError = true)
        activeRuntimeThreadId = null
        runtimeNeedsBootstrap = true
        refreshMemorySnapshot()
    }

    private fun hasCompleteLlmModelFile(definition: LocalLlmDefinition): Boolean {
        val file = File(definition.modelPath)
        return file.exists() && file.length() >= definition.minimumFileSizeBytes
    }

    private fun selectedLlmDefinition(): LocalLlmDefinition? {
        return llmDefinitions.firstOrNull { it.id == _uiState.value.selectedLlmId } ?: llmDefinitions.firstOrNull()
    }

    private fun loadedLlmDefinition(): LocalLlmDefinition? {
        val loadedId = _uiState.value.loadedLlmId ?: return null
        return llmDefinitions.firstOrNull { it.id == loadedId }
    }

    private fun toLlmModelOption(definition: LocalLlmDefinition): LlmModelOption {
        return LlmModelOption(
            id = definition.id,
            displayName = definition.displayName,
            assistantLabel = definition.assistantLabel,
            providerLabel = definition.providerLabel,
            runtimeLabel = definition.runtimeLabel,
            description = definition.description,
            modelPath = definition.modelPath,
            isLoadable = definition.isLoadable,
            implementationNote = definition.implementationNote
        )
    }

    private fun releaseCurrentLlmEngine() {
        app.llmEngine?.close()
        app.llmEngine = null
    }

    private fun clearLlmLoadedState(clearChat: Boolean, clearLastError: Boolean) {
        if (clearChat) {
            _chatMessages.value = emptyList()
        }
        _uiState.update { current ->
            current.copy(
                llmReady = false,
                loadedLlmId = null,
                lastError = if (clearLastError) null else current.lastError,
                llmRuntimeInfo = "",
                llmBenchmarkResult = null,
                isRunningBenchmark = false
            )
        }
    }

    private fun loadLlmModelInternal(definition: LocalLlmDefinition, showMissingErrors: Boolean) {
        if (_uiState.value.isProcessing) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedLlmId = definition.id,
                    llmModelPath = definition.modelPath,
                    isLoadingLlmModel = true,
                    isProcessing = true,
                    lastError = null
                )
            }

            if (!definition.isLoadable) {
                _uiState.update {
                        it.copy(
                            isLoadingLlmModel = false,
                            isProcessing = false,
                            lastError = definition.implementationNote
                                ?: "${definition.displayName} はまだ接続されていません。"
                    )
                }
                return@launch
            }

            val file = File(definition.modelPath)
            Log.d("XtrustVM", "checking path: ${definition.modelPath} exists=${file.exists()} size=${file.length()}")
            when {
                !file.exists() ->
                    _uiState.update {
                        it.copy(
                            isLoadingLlmModel = false,
                            isProcessing = false,
                            lastError = if (showMissingErrors) {
                                "${definition.displayName} のモデルファイルが見つかりません。配置を確認してください。"
                            } else {
                                null
                            }
                        )
                    }
                file.length() < definition.minimumFileSizeBytes ->
                    _uiState.update {
                        it.copy(
                            isLoadingLlmModel = false,
                            isProcessing = false,
                            lastError = if (showMissingErrors) {
                                "${definition.displayName} のモデルファイルが不完全です（${file.length() / 1_000_000}MB）。転送完了後に再試行してください。"
                            } else {
                                null
                            }
                        )
                    }
                else -> try {
                    releaseCurrentLlmEngine()
                    clearLlmLoadedState(clearChat = false, clearLastError = false)

                    val engine = checkNotNull(definition.engineFactory).invoke(getApplication())
                    engine.initialize(definition.modelPath)
                    llmEngine = engine
                    val runtimeInfo = (engine as? LlmDiagnostics)?.runtimeInfo().orEmpty()

                    _uiState.update {
                        it.copy(
                            llmReady = true,
                            loadedLlmId = definition.id,
                            llmModelPath = definition.modelPath,
                            isLoadingLlmModel = false,
                            isProcessing = false,
                            llmRuntimeInfo = runtimeInfo,
                            llmBenchmarkResult = null,
                            isRunningBenchmark = false
                        )
                    }
                    resetRuntimeConversationForSelectedThread()
                    refreshMemorySnapshot()
                } catch (e: Exception) {
                    releaseCurrentLlmEngine()
                    clearLlmLoadedState(clearChat = false, clearLastError = false)
                    activeRuntimeThreadId = null
                    runtimeNeedsBootstrap = true
                    _uiState.update {
                        it.copy(
                            isLoadingLlmModel = false,
                            isProcessing = false,
                            lastError = "ロード失敗: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun runLlmBenchmark() {
        val engine = llmEngine as? LlmDiagnostics
        if (engine == null) {
            _uiState.update {
                it.copy(lastError = "このランタイムはベンチマーク未対応です。")
            }
            return
        }
        if (_uiState.value.isProcessing || _uiState.value.isRunningBenchmark) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunningBenchmark = true,
                    lastError = null,
                    llmBenchmarkResult = null
                )
            }
            try {
                val result = engine.benchmark()
                val runtimeInfo = engine.runtimeInfo()
                _uiState.update {
                    it.copy(
                        llmRuntimeInfo = runtimeInfo,
                        llmBenchmarkResult = result,
                        isRunningBenchmark = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRunningBenchmark = false,
                        lastError = "ベンチマーク失敗: ${e.message}"
                    )
                }
            } finally {
                refreshMemorySnapshot()
            }
        }
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
        val engine = llmEngine ?: return
        val loadedDefinition = loadedLlmDefinition() ?: return
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
                val result = engine.generate(prompt)
                val topic = TopicEntity(
                    sessionId = currentOrLatestSessionId(),
                    title = result.lines().firstOrNull() ?: "Topic",
                    summary = result,
                    startAt = System.currentTimeMillis(),
                    endAt = System.currentTimeMillis(),
                    llmProvider = loadedDefinition.dbProvider,
                    llmModel = loadedDefinition.dbModel
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
        val engine = llmEngine
        if (engine == null || !engine.isReady) {
            val selectedName = selectedLlmDefinition()?.displayName ?: "ローカル LLM"
            _uiState.update { it.copy(lastError = "$selectedName が未ロードです。設定画面で確認してください。") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, lastError = null) }
            try {
                val threadId = ensureSelectedChatThreadId()
                val existingMessages = repository.loadChatMessages(threadId)
                val loadedModel = loadedLlmDefinition()?.dbModel
                repository.saveChatMessage(threadId, CHAT_ROLE_USER, trimmed, loadedModel)
                if (existingMessages.isEmpty()) {
                    repository.updateChatThreadTitle(threadId, buildChatThreadTitle(trimmed))
                }
                syncChatThreadState(threadId)
                val reply = if (shouldBootstrapThread(threadId) && existingMessages.isNotEmpty()) {
                    engine.generate(buildChatPrompt(_chatMessages.value))
                } else {
                    if (activeRuntimeThreadId != threadId) {
                        engine.resetChat()
                    }
                    engine.sendChatMessage(trimmed)
                }
                repository.saveChatMessage(threadId, CHAT_ROLE_ASSISTANT, reply, loadedModel)
                activeRuntimeThreadId = threadId
                runtimeNeedsBootstrap = false
                syncChatThreadState(threadId)
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = "チャット失敗: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
                refreshMemorySnapshot()
            }
        }
    }

    fun resetChat() {
        createChatThread()
    }

    fun createChatThread() {
        viewModelScope.launch {
            val threadId = repository.createChatThread(
                title = DEFAULT_CHAT_THREAD_TITLE,
                llmModel = loadedLlmDefinition()?.dbModel
            )
            syncChatThreadState(threadId)
            resetRuntimeConversationForSelectedThread()
        }
    }

    fun selectChatThread(threadId: Long) {
        viewModelScope.launch {
            syncChatThreadState(threadId)
            resetRuntimeConversationForSelectedThread()
        }
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
            enqueueWrapupJob(sessionId)
        }
    }

    private suspend fun enqueueWrapupJob(sessionId: Long) {
        val transcript = repository.getSessionTranscript(sessionId)
        if (transcript.isBlank()) return
        val llmModel = loadedLlmDefinition()?.dbModel
        val jobId = repository.enqueueWrapupJob(sessionId, llmModel)
        SessionWrapupService.start(getApplication(), sessionId, jobId)
        startWrapupPolling()
    }

    fun cancelWrapup(sessionId: Long) {
        viewModelScope.launch {
            val job = repository.loadWrapupJobForSession(sessionId)
            if (job != null) {
                repository.cancelWrapupJob(job.id)
            }
            SessionWrapupService.cancel(getApplication())
            _uiState.update {
                it.copy(wrapupJobBySession = it.wrapupJobBySession - sessionId)
            }
            wrapupPollingJob?.cancel()
        }
    }

    fun retryWrapup(sessionId: Long) {
        viewModelScope.launch {
            enqueueWrapupJob(sessionId)
        }
    }

    private fun startWrapupPolling() {
        wrapupPollingJob?.cancel()
        wrapupPollingJob = viewModelScope.launch {
            while (isActive) {
                val jobs = repository.loadPendingWrapupJobs()
                if (jobs.isEmpty()) {
                    refreshWrapupState()
                    break
                }
                _uiState.update { state ->
                    state.copy(wrapupJobBySession = jobs.associateBy { it.sessionId })
                }
                delay(2000)
            }
        }
    }

    private suspend fun refreshWrapupState() {
        val jobs = repository.loadPendingWrapupJobs()
        _uiState.update { state ->
            state.copy(wrapupJobBySession = jobs.associateBy { it.sessionId })
        }
    }

    private suspend fun resumePendingWrapupJobs() {
        val pending = repository.loadPendingWrapupJobs()
        if (pending.isEmpty()) return
        _uiState.update { state ->
            state.copy(wrapupJobBySession = pending.associateBy { it.sessionId })
        }
        pending.forEach { job ->
            SessionWrapupService.start(getApplication(), job.sessionId, job.id)
        }
        startWrapupPolling()
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

    private fun shouldBootstrapThread(threadId: Long): Boolean {
        return runtimeNeedsBootstrap || activeRuntimeThreadId != threadId
    }

    private fun resetRuntimeConversationForSelectedThread() {
        if (llmEngine?.isReady == true) {
            llmEngine?.resetChat()
        }
        activeRuntimeThreadId = _uiState.value.selectedChatThreadId
        runtimeNeedsBootstrap = _chatMessages.value.isNotEmpty()
    }

    private suspend fun ensureChatThreadSelection() {
        val threads = repository.loadChatThreads()
        if (threads.isEmpty()) {
            val threadId = repository.createChatThread(
                title = DEFAULT_CHAT_THREAD_TITLE,
                llmModel = loadedLlmDefinition()?.dbModel
            )
            syncChatThreadState(threadId)
            return
        }
        val selectedId = _uiState.value.selectedChatThreadId
            ?.takeIf { id -> threads.any { it.id == id } }
            ?: threads.first().id
        syncChatThreadState(selectedId, threads)
    }

    private suspend fun ensureSelectedChatThreadId(): Long {
        val selectedId = _uiState.value.selectedChatThreadId
        if (selectedId != null) return selectedId
        val threadId = repository.createChatThread(
            title = DEFAULT_CHAT_THREAD_TITLE,
            llmModel = loadedLlmDefinition()?.dbModel
        )
        syncChatThreadState(threadId)
        return threadId
    }

    private suspend fun syncChatThreadState(
        selectedThreadId: Long
    ) {
        syncChatThreadState(selectedThreadId, repository.loadChatThreads())
    }

    private suspend fun syncChatThreadState(
        selectedThreadId: Long,
        threads: List<ChatThreadEntity>
    ) {
        val selectedId = threads.firstOrNull { it.id == selectedThreadId }?.id
            ?: threads.firstOrNull()?.id
            ?: return
        val messages = repository.loadChatMessages(selectedId)
        _chatMessages.value = messages.map(::toUiChatMessage)
        _uiState.update {
            it.copy(
                selectedChatThreadId = selectedId,
                chatThreads = threads.map { thread -> toChatThreadItem(thread, thread.id == selectedId) }
            )
        }
    }

    private fun toUiChatMessage(message: StoredChatMessageEntity): ChatMessage {
        return ChatMessage(
            id = message.id,
            role = if (message.role == CHAT_ROLE_USER) ChatRole.User else ChatRole.Assistant,
            text = message.text,
            createdAt = message.createdAt
        )
    }

    private fun toChatThreadItem(thread: ChatThreadEntity, isSelected: Boolean): ChatThreadItem {
        return ChatThreadItem(
            id = thread.id,
            title = thread.title,
            updatedAtLabel = formatChatThreadTimestamp(thread.updatedAt),
            isSelected = isSelected
        )
    }

    private fun formatChatThreadTimestamp(timestamp: Long): String {
        val format = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        return format.format(Date(timestamp))
    }

    private fun buildChatThreadTitle(firstUserMessage: String): String {
        val normalized = firstUserMessage
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return normalized
            .take(24)
            .ifBlank { DEFAULT_CHAT_THREAD_TITLE }
    }

    private fun buildChatPrompt(messages: List<ChatMessage>): String {
        val recentMessages = messages.takeLast(MAX_CHAT_HISTORY_MESSAGES)
        val transcript = recentMessages.joinToString(separator = "\n") { message ->
            val role = if (message.role == ChatRole.User) "User" else "Assistant"
            "$role: ${message.text}"
        }
        return """
            Continue the conversation below.
            Reply as the assistant only.
            Reply in the same language as the latest user message.
            Be concise, direct, and do not output hidden reasoning or XML-like tags.

            Conversation:
            $transcript

            Assistant:
        """.trimIndent()
    }

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
        releaseCurrentLlmEngine()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SEGMENT_DURATION_MS = 900L
        const val AUTO_TRANSCRIBE_SEGMENTS = true
        const val DEFAULT_CHAT_THREAD_TITLE = "新しいチャット"
        const val MAX_CHAT_HISTORY_MESSAGES = 8
        const val CHAT_ROLE_USER = "user"
        const val CHAT_ROLE_ASSISTANT = "assistant"
        val KNOWN_ASR_MODEL_DIR_NAMES = listOf(
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        )
    }
}
