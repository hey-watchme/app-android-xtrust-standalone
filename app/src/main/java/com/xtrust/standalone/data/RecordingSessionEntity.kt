package com.xtrust.standalone.data

data class RecordingSessionEntity(
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RecordingSessionSummary(
    val session: RecordingSessionEntity,
    val segmentCount: Int,
    val transcribedCount: Int
)
