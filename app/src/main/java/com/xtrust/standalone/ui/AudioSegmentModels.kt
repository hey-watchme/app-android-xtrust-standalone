package com.xtrust.standalone.ui

data class AudioSegment(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val transcript: String? = null,
    val isTranscribing: Boolean = false,
    val asrError: String? = null,
    val transcriptionMs: Long? = null,
    val realTimeFactor: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)
