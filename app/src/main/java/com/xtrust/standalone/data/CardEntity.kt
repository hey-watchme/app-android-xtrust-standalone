package com.xtrust.standalone.data

data class CardEntity(
    val id: Long = 0,
    val sessionId: Long,
    val audioPath: String,
    val transcript: String? = null,
    val asrProvider: String,
    val asrModel: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val recordedAt: Long,
    val transcriptionMs: Long? = null,
    val realTimeFactor: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
