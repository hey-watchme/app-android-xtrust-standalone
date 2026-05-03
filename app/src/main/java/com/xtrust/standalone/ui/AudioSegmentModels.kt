package com.xtrust.standalone.ui

data class AudioSegment(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)
