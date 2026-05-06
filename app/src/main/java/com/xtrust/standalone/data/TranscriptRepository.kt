package com.xtrust.standalone.data

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TranscriptRepository(context: Context) {

    private val dbHelper = AppDatabaseHelper(context)

    private val _cards = MutableStateFlow<List<CardEntity>>(emptyList())
    private val _topics = MutableStateFlow<List<TopicEntity>>(emptyList())
    private val _sessions = MutableStateFlow<List<RecordingSessionSummary>>(emptyList())

    val cards: Flow<List<CardEntity>> = _cards.asStateFlow()
    val topics: Flow<List<TopicEntity>> = _topics.asStateFlow()
    val sessions: Flow<List<RecordingSessionSummary>> = _sessions.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _cards.value = loadCards()
        _topics.value = loadTopics()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun recoverDanglingSessions(endedAt: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                DELETE FROM sessions
                WHERE status = ? AND ended_at IS NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM cards
                    WHERE cards.session_id = sessions.id
                  )
                """.trimIndent(),
                arrayOf<Any>(STATUS_RECORDING)
            )
            val values = ContentValues().apply {
                put("ended_at", endedAt)
                put("status", STATUS_COMPLETED)
                putNull("error_message")
                put("updated_at", endedAt)
            }
            db.update(
                "sessions",
                values,
                "status = ? AND ended_at IS NULL",
                arrayOf(STATUS_RECORDING)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _sessions.value = loadSessionSummaries()
    }

    suspend fun finalizeSession(
        sessionId: Long,
        endedAt: Long,
        status: String,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("status", status)
            if (errorMessage.isNullOrBlank()) {
                putNull("error_message")
            } else {
                put("error_message", errorMessage)
            }
            put("updated_at", endedAt)
        }
        dbHelper.writableDatabase.update(
            "sessions",
            values,
            "id = ?",
            arrayOf(sessionId.toString())
        )
        _sessions.value = loadSessionSummaries()
    }

    suspend fun completeOrDeleteEmptySession(
        sessionId: Long,
        endedAt: Long
    ) = withContext(Dispatchers.IO) {
        if (hasCardsForSession(sessionId)) {
            finalizeSession(sessionId, endedAt, STATUS_COMPLETED)
        } else {
            deleteSession(sessionId)
        }
    }

    suspend fun failSession(
        sessionId: Long,
        endedAt: Long,
        errorMessage: String
    ) = withContext(Dispatchers.IO) {
        finalizeSession(
            sessionId = sessionId,
            endedAt = endedAt,
            status = STATUS_ERROR,
            errorMessage = errorMessage
        )
    }

    suspend fun startSession(startedAt: Long): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", AppDatabaseHelper.buildSessionTitle(startedAt))
            put("started_at", startedAt)
            putNull("ended_at")
            put("status", STATUS_RECORDING)
            putNull("error_message")
            put("created_at", now)
            put("updated_at", now)
        }
        val sessionId = dbHelper.writableDatabase.insertOrThrow("sessions", null, values)
        _sessions.value = loadSessionSummaries()
        sessionId
    }

    suspend fun completeSession(sessionId: Long, endedAt: Long) = withContext(Dispatchers.IO) {
        finalizeSession(sessionId, endedAt, STATUS_COMPLETED)
    }

    suspend fun discardSession(sessionId: Long) = withContext(Dispatchers.IO) {
        deleteSession(sessionId)
    }

    suspend fun saveCard(card: CardEntity): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("session_id", card.sessionId)
            put("audio_path", card.audioPath)
            put("transcript", card.transcript)
            put("asr_provider", card.asrProvider)
            put("asr_model", card.asrModel)
            put("duration_ms", card.durationMs)
            put("size_bytes", card.sizeBytes)
            put("recorded_at", card.recordedAt)
            put("transcription_ms", card.transcriptionMs)
            put("real_time_factor", card.realTimeFactor)
            put("created_at", card.createdAt)
            put("updated_at", now)
        }
        val id = dbHelper.writableDatabase.insertOrThrow("cards", null, values)
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
        id
    }

    suspend fun deleteCard(cardId: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            "cards",
            "id = ?",
            arrayOf(cardId.toString())
        )
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun updateCardTranscription(
        cardId: Long,
        transcript: String,
        transcriptionMs: Long,
        realTimeFactor: Double
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("transcript", transcript)
            put("transcription_ms", transcriptionMs)
            put("real_time_factor", realTimeFactor)
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update(
            "cards",
            values,
            "id = ?",
            arrayOf(cardId.toString())
        )
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun markCardTranscriptionFailed(
        cardId: Long,
        errorMessage: String,
        transcriptionMs: Long
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("transcript", "[ASR失敗] ${errorMessage.take(80)}")
            put("transcription_ms", transcriptionMs)
            putNull("real_time_factor")
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update(
            "cards",
            values,
            "id = ?",
            arrayOf(cardId.toString())
        )
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun loadCard(cardId: Long): CardEntity? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "cards",
            null,
            "id = ?",
            arrayOf(cardId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            CardEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                asrProvider = cursor.getString(cursor.getColumnIndexOrThrow("asr_provider")),
                asrModel = cursor.getString(cursor.getColumnIndexOrThrow("asr_model")),
                durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
                recordedAt = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at")),
                transcriptionMs = cursor.getLongOrNull("transcription_ms"),
                realTimeFactor = cursor.getDoubleOrNull("real_time_factor"),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    suspend fun loadOldestPendingCard(): CardEntity? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "cards",
            null,
            "transcript IS NULL OR transcript = ''",
            null,
            null,
            null,
            "recorded_at ASC, id ASC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            CardEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                asrProvider = cursor.getString(cursor.getColumnIndexOrThrow("asr_provider")),
                asrModel = cursor.getString(cursor.getColumnIndexOrThrow("asr_model")),
                durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
                recordedAt = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at")),
                transcriptionMs = cursor.getLongOrNull("transcription_ms"),
                realTimeFactor = cursor.getDoubleOrNull("real_time_factor"),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    suspend fun saveTopic(topic: TopicEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("session_id", topic.sessionId)
            put("title", topic.title)
            put("summary", topic.summary)
            put("start_at", topic.startAt)
            put("end_at", topic.endAt)
            put("llm_provider", topic.llmProvider)
            put("llm_model", topic.llmModel)
            put("created_at", topic.createdAt)
        }
        val id = dbHelper.writableDatabase.insertOrThrow("topics", null, values)
        _topics.value = loadTopics()
        id
    }

    suspend fun loadChatThreads(): List<ChatThreadEntity> = withContext(Dispatchers.IO) {
        loadChatThreadsInternal()
    }

    suspend fun loadChatMessages(threadId: Long): List<ChatMessageEntity> = withContext(Dispatchers.IO) {
        loadChatMessagesInternal(threadId)
    }

    suspend fun createChatThread(
        title: String,
        llmModel: String?
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title)
            if (llmModel.isNullOrBlank()) {
                putNull("llm_model")
            } else {
                put("llm_model", llmModel)
            }
            put("created_at", now)
            put("updated_at", now)
        }
        dbHelper.writableDatabase.insertOrThrow("chat_threads", null, values)
    }

    suspend fun updateChatThreadTitle(
        threadId: Long,
        title: String
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update(
            "chat_threads",
            values,
            "id = ?",
            arrayOf(threadId.toString())
        )
    }

    suspend fun saveChatMessage(
        threadId: Long,
        role: String,
        text: String,
        llmModel: String?
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        val messageValues = ContentValues().apply {
            put("thread_id", threadId)
            put("role", role)
            put("text", text)
            put("created_at", now)
        }
        val messageId = db.insertOrThrow("chat_messages", null, messageValues)
        val threadValues = ContentValues().apply {
            put("updated_at", now)
            if (!llmModel.isNullOrBlank()) {
                put("llm_model", llmModel)
            }
        }
        db.update(
            "chat_threads",
            threadValues,
            "id = ?",
            arrayOf(threadId.toString())
        )
        messageId
    }

    private fun loadCards(): List<CardEntity> {
        val db = dbHelper.readableDatabase
        val cards = mutableListOf<CardEntity>()
        db.query(
            "cards",
            null,
            null,
            null,
            null,
            null,
            "recorded_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cards += CardEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                    audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                    asrProvider = cursor.getString(cursor.getColumnIndexOrThrow("asr_provider")),
                    asrModel = cursor.getString(cursor.getColumnIndexOrThrow("asr_model")),
                    durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
                    recordedAt = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at")),
                    transcriptionMs = cursor.getLongOrNull("transcription_ms"),
                    realTimeFactor = cursor.getDoubleOrNull("real_time_factor"),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                )
            }
        }
        return cards
    }

    private fun loadTopics(): List<TopicEntity> {
        val db = dbHelper.readableDatabase
        val topics = mutableListOf<TopicEntity>()
        db.query(
            "topics",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                topics += TopicEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sessionId = cursor.getLongOrNull("session_id"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                    startAt = cursor.getLong(cursor.getColumnIndexOrThrow("start_at")),
                    endAt = cursor.getLong(cursor.getColumnIndexOrThrow("end_at")),
                    llmProvider = cursor.getString(cursor.getColumnIndexOrThrow("llm_provider")),
                    llmModel = cursor.getString(cursor.getColumnIndexOrThrow("llm_model")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return topics
    }

    private fun loadSessionSummaries(): List<RecordingSessionSummary> {
        val db = dbHelper.readableDatabase
        val sessions = mutableListOf<RecordingSessionSummary>()
        db.rawQuery(
            """
            SELECT
                s.id,
                s.title,
                s.started_at,
                s.ended_at,
                s.status,
                s.error_message,
                s.created_at,
                s.updated_at,
                COUNT(c.id) AS segment_count,
                SUM(CASE WHEN c.transcript IS NOT NULL AND c.transcript != '' THEN 1 ELSE 0 END) AS transcribed_count
            FROM sessions s
            LEFT JOIN cards c ON c.session_id = s.id
            GROUP BY s.id
            ORDER BY s.started_at DESC, s.id DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += RecordingSessionSummary(
                    session = RecordingSessionEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                        endedAt = cursor.getLongOrNull("ended_at"),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        errorMessage = cursor.getStringOrNull("error_message"),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    ),
                    segmentCount = cursor.getInt(cursor.getColumnIndexOrThrow("segment_count")),
                    transcribedCount = cursor.getIntOrZero("transcribed_count")
                )
            }
        }
        return sessions
    }

    private fun loadChatThreadsInternal(): List<ChatThreadEntity> {
        val db = dbHelper.readableDatabase
        val threads = mutableListOf<ChatThreadEntity>()
        db.query(
            "chat_threads",
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                threads += ChatThreadEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    llmModel = cursor.getStringOrNull("llm_model"),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                )
            }
        }
        return threads
    }

    private fun loadChatMessagesInternal(threadId: Long): List<ChatMessageEntity> {
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<ChatMessageEntity>()
        db.query(
            "chat_messages",
            null,
            "thread_id = ?",
            arrayOf(threadId.toString()),
            null,
            null,
            "created_at ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessageEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id")),
                    role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return messages
    }

    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getDoubleOrNull(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.getIntOrZero(columnName: String): Int {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) 0 else getInt(index)
    }

    private fun hasCardsForSession(sessionId: Long): Boolean {
        return dbHelper.readableDatabase.rawQuery(
            """
            SELECT EXISTS(
                SELECT 1
                FROM cards
                WHERE session_id = ?
            )
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    private fun deleteSession(sessionId: Long) {
        dbHelper.writableDatabase.delete(
            "sessions",
            "id = ?",
            arrayOf(sessionId.toString())
        )
        _sessions.value = loadSessionSummaries()
    }

    suspend fun enqueueWrapupJob(sessionId: Long, llmModel: String?): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dbHelper.writableDatabase.delete("session_wrapup_jobs", "session_id = ?", arrayOf(sessionId.toString()))
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("status", WrapupJobEntity.STATUS_PENDING)
            putNull("current_step")
            putNull("step_detail")
            put("attempts", 0)
            putNull("last_error")
            if (llmModel != null) put("llm_model", llmModel) else putNull("llm_model")
            put("enqueued_at", now)
            putNull("started_at")
            putNull("finished_at")
        }
        dbHelper.writableDatabase.insertOrThrow("session_wrapup_jobs", null, values)
    }

    suspend fun updateWrapupJobStep(
        jobId: Long,
        status: String,
        step: String?,
        detail: String?
    ) = withContext(Dispatchers.IO) {
        val existingStartedAt = dbHelper.readableDatabase.rawQuery(
            "SELECT started_at FROM session_wrapup_jobs WHERE id = ?",
            arrayOf(jobId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        }
        val values = ContentValues().apply {
            put("status", status)
            if (step != null) put("current_step", step) else putNull("current_step")
            if (detail != null) put("step_detail", detail) else putNull("step_detail")
            if (status == WrapupJobEntity.STATUS_RUNNING && existingStartedAt == null) {
                put("started_at", System.currentTimeMillis())
            }
        }
        dbHelper.writableDatabase.update("session_wrapup_jobs", values, "id = ?", arrayOf(jobId.toString()))
    }

    suspend fun failWrapupJob(jobId: Long, error: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", WrapupJobEntity.STATUS_FAILED)
            put("last_error", error)
            put("finished_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update("session_wrapup_jobs", values, "id = ?", arrayOf(jobId.toString()))
    }

    suspend fun completeWrapupJob(jobId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", WrapupJobEntity.STATUS_COMPLETED)
            put("current_step", "done")
            put("finished_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update("session_wrapup_jobs", values, "id = ?", arrayOf(jobId.toString()))
    }

    suspend fun cancelWrapupJob(jobId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", WrapupJobEntity.STATUS_CANCELED)
            put("finished_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update("session_wrapup_jobs", values, "id = ?", arrayOf(jobId.toString()))
    }

    suspend fun loadPendingWrapupJobs(): List<WrapupJobEntity> = withContext(Dispatchers.IO) {
        loadWrapupJobsByQuery(
            "SELECT * FROM session_wrapup_jobs WHERE status IN (?, ?) ORDER BY enqueued_at ASC",
            arrayOf(WrapupJobEntity.STATUS_PENDING, WrapupJobEntity.STATUS_RUNNING)
        )
    }

    suspend fun loadAllWrapupJobs(): List<WrapupJobEntity> = withContext(Dispatchers.IO) {
        loadWrapupJobsByQuery(
            "SELECT * FROM session_wrapup_jobs ORDER BY enqueued_at DESC LIMIT 200",
            emptyArray()
        )
    }

    private fun loadWrapupJobsByQuery(sql: String, args: Array<String>): List<WrapupJobEntity> {
        val jobs = mutableListOf<WrapupJobEntity>()
        dbHelper.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                jobs += WrapupJobEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    currentStep = cursor.getStringOrNull("current_step"),
                    stepDetail = cursor.getStringOrNull("step_detail"),
                    attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
                    lastError = cursor.getStringOrNull("last_error"),
                    llmModel = cursor.getStringOrNull("llm_model"),
                    enqueuedAt = cursor.getLong(cursor.getColumnIndexOrThrow("enqueued_at")),
                    startedAt = cursor.getLongOrNull("started_at"),
                    finishedAt = cursor.getLongOrNull("finished_at")
                )
            }
        }
        return jobs
    }

    suspend fun saveSessionSummary(summary: SessionSummaryEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("session_id", summary.sessionId)
            if (summary.generatedTitle != null) put("generated_title", summary.generatedTitle) else putNull("generated_title")
            if (summary.theme != null) put("theme", summary.theme) else putNull("theme")
            if (summary.agendaJson != null) put("agenda_json", summary.agendaJson) else putNull("agenda_json")
            if (summary.llmProvider != null) put("llm_provider", summary.llmProvider) else putNull("llm_provider")
            if (summary.llmModel != null) put("llm_model", summary.llmModel) else putNull("llm_model")
            put("generated_at", summary.generatedAt)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "session_summaries",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun loadSessionSummary(sessionId: Long): SessionSummaryEntity? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM session_summaries WHERE session_id = ?",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            SessionSummaryEntity(
                sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                generatedTitle = cursor.getStringOrNull("generated_title"),
                theme = cursor.getStringOrNull("theme"),
                agendaJson = cursor.getStringOrNull("agenda_json"),
                llmProvider = cursor.getStringOrNull("llm_provider"),
                llmModel = cursor.getStringOrNull("llm_model"),
                generatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("generated_at"))
            )
        }
    }

    suspend fun loadAllSessionSummaries(): List<SessionSummaryEntity> = withContext(Dispatchers.IO) {
        val summaries = mutableListOf<SessionSummaryEntity>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM session_summaries ORDER BY generated_at DESC",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                summaries += SessionSummaryEntity(
                    sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                    generatedTitle = cursor.getStringOrNull("generated_title"),
                    theme = cursor.getStringOrNull("theme"),
                    agendaJson = cursor.getStringOrNull("agenda_json"),
                    llmProvider = cursor.getStringOrNull("llm_provider"),
                    llmModel = cursor.getStringOrNull("llm_model"),
                    generatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("generated_at"))
                )
            }
        }
        summaries
    }

    suspend fun loadWrapupJobForSession(sessionId: Long): WrapupJobEntity? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM session_wrapup_jobs WHERE session_id = ?",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            WrapupJobEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                currentStep = cursor.getStringOrNull("current_step"),
                stepDetail = cursor.getStringOrNull("step_detail"),
                attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
                lastError = cursor.getStringOrNull("last_error"),
                llmModel = cursor.getStringOrNull("llm_model"),
                enqueuedAt = cursor.getLong(cursor.getColumnIndexOrThrow("enqueued_at")),
                startedAt = cursor.getLongOrNull("started_at"),
                finishedAt = cursor.getLongOrNull("finished_at")
            )
        }
    }

    suspend fun getSessionTranscript(sessionId: Long): String = withContext(Dispatchers.IO) {
        val texts = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT transcript FROM cards WHERE session_id = ? AND transcript IS NOT NULL AND transcript != '' ORDER BY recorded_at ASC, id ASC",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(0)
                if (!text.isNullOrBlank()) texts += text
            }
        }
        texts.joinToString("\n")
    }

    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_ERROR = "error"
    }
}
