package com.xtrust.standalone.wrapup

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.xtrust.standalone.XtrustApplication
import com.xtrust.standalone.data.SessionSummaryEntity
import com.xtrust.standalone.data.TranscriptRepository
import com.xtrust.standalone.data.WrapupJobEntity
import com.xtrust.standalone.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class SessionWrapupService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationHelper: WrapupNotificationHelper
    private lateinit var repository: TranscriptRepository
    private var currentJobId: Long = -1L
    private var wrapupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = WrapupNotificationHelper(this)
        repository = TranscriptRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelCurrentJob()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L) ?: -1L
        if (sessionId < 0 || jobId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentJobId = jobId
        startForeground(
            WrapupNotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildProgressNotification("準備中…", 1)
        )

        wrapupJob = serviceScope.launch {
            runWrapup(sessionId, jobId)
        }

        return START_REDELIVER_INTENT
    }

    private fun cancelCurrentJob() {
        val jobId = currentJobId
        wrapupJob?.cancel()
        (applicationContext as? XtrustApplication)?.llmEngine?.cancel()
        if (jobId >= 0L) {
            serviceScope.launch {
                repository.cancelWrapupJob(jobId)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runWrapup(sessionId: Long, jobId: Long) {
        val app = applicationContext as XtrustApplication
        try {
            // Step 1: transcript 収集
            postProgress("transcript収集中", 1, jobId, "collect")
            val transcript = repository.getSessionTranscript(sessionId)
            if (transcript.isBlank()) {
                finish(jobId, error = "文字起こし結果がありません")
                return
            }

            // Step 2: LLM 確認
            postProgress("LLM準備中…", 2, jobId, "prepare")
            val engine = app.llmEngine
            if (engine == null || !engine.isReady) {
                finish(jobId, error = "LLMがロードされていません")
                return
            }

            // Step 3: 要約生成（秒カウントを通知に出す）
            postProgress("要約生成中…", 3, jobId, "generate")
            var elapsedSec = 0
            val tickJob = serviceScope.launch {
                while (isActive) {
                    delay(1000)
                    elapsedSec++
                    notificationHelper.notify(
                        notificationHelper.buildGeneratingNotification(elapsedSec)
                    )
                    repository.updateWrapupJobStep(
                        jobId = jobId,
                        status = WrapupJobEntity.STATUS_RUNNING,
                        step = "generate",
                        detail = "要約生成中… (${elapsedSec}秒)"
                    )
                }
            }

            var summaryJson: String? = null
            try {
                app.llmMutex.withLock {
                    AppLog.d(TAG, "LLM generate start. transcript_len=${transcript.length}")
                    summaryJson = withTimeoutOrNull(WRAPUP_TIMEOUT_MS) {
                        engine.generate(buildPrompt(transcript))
                    }
                    AppLog.d(TAG, "LLM generate done. result=${summaryJson?.take(300) ?: "NULL(timeout)"}")
                }
            } finally {
                tickJob.cancel()
            }

            if (summaryJson == null) {
                AppLog.e(TAG, "Wrapup timed out after ${WRAPUP_TIMEOUT_MS / 1000}s for session=$sessionId")
                finish(jobId, error = "タイムアウト (${WRAPUP_TIMEOUT_MS / 60_000}分)")
                return
            }

            // Step 4: 保存
            postProgress("保存中…", 4, jobId, "save")
            val (title, theme, agendaJson) = parseSummaryJson(summaryJson!!)
            repository.saveSessionSummary(
                SessionSummaryEntity(
                    sessionId = sessionId,
                    generatedTitle = title,
                    theme = theme,
                    agendaJson = agendaJson,
                    llmProvider = "bonsai",
                    llmModel = "Bonsai-8B",
                    generatedAt = System.currentTimeMillis()
                )
            )
            repository.completeWrapupJob(jobId)

            notificationHelper.notify(
                notificationHelper.buildCompletedNotification(title ?: "議事録")
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (e: Exception) {
            AppLog.e(TAG, "wrapup failed for session=$sessionId", e)
            finish(jobId, error = e.message ?: "不明なエラー")
        }
    }

    private suspend fun postProgress(text: String, step: Int, jobId: Long, stepName: String) {
        notificationHelper.notify(notificationHelper.buildProgressNotification(text, step))
        repository.updateWrapupJobStep(
            jobId = jobId,
            status = WrapupJobEntity.STATUS_RUNNING,
            step = stepName,
            detail = text
        )
    }

    private suspend fun finish(jobId: Long, error: String) {
        repository.failWrapupJob(jobId, error)
        notificationHelper.notify(notificationHelper.buildFailedNotification(error))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildPrompt(transcript: String): String {
        val truncated = if (transcript.length > 3000) transcript.takeLast(3000) else transcript
        return """
以下の会議の発言録を読んで、必ず次のJSON形式のみで回答してください。日本語で回答し、余分な説明や前置きは不要です。

{"title":"...","theme":"...","agenda":["...","..."]}

- title: 会議のタイトル（15文字以内）
- theme: 会議の主なテーマや目的（50文字以内）
- agenda: 議論されたトピックのリスト（3〜5個）

発言録:
$truncated
        """.trimIndent()
    }

    private fun parseSummaryJson(raw: String): Triple<String?, String?, String?> {
        return try {
            val s = raw.indexOf('{')
            val e = raw.lastIndexOf('}')
            if (s < 0 || e < 0) {
                AppLog.w(TAG, "No JSON braces found in LLM output: ${raw.take(200)}")
                return Triple(null, raw.take(80).ifBlank { null }, null)
            }
            val jsonStr = raw.substring(s, e + 1)
            AppLog.d(TAG, "Parsed JSON candidate: $jsonStr")
            val json = JSONObject(jsonStr)
            val title = json.optString("title").ifBlank { null }
            val theme = json.optString("theme").ifBlank { null }
            val agenda = json.optJSONArray("agenda") ?: JSONArray()
            AppLog.d(TAG, "Summary OK — title=$title theme=$theme agenda=$agenda")
            Triple(title, theme, agenda.toString())
        } catch (ex: Exception) {
            AppLog.w(TAG, "JSON parse failed: ${ex.message}  raw=${raw.take(200)}")
            Triple(null, raw.take(80).ifBlank { null }, null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "SessionWrapupService"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_JOB_ID = "job_id"
        const val ACTION_CANCEL = "com.xtrust.standalone.CANCEL_WRAPUP"
        private const val WRAPUP_TIMEOUT_MS = 5 * 60 * 1000L

        fun start(context: Context, sessionId: Long, jobId: Long) {
            context.startForegroundService(
                Intent(context, SessionWrapupService::class.java).apply {
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_JOB_ID, jobId)
                }
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, SessionWrapupService::class.java).apply {
                    action = ACTION_CANCEL
                }
            )
        }
    }
}
