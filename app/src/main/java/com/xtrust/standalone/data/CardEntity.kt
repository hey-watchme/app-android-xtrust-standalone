package com.xtrust.standalone.data

data class CardEntity(
    val id: Long = 0,
    val audioPath: String,
    val transcript: String,
    val asrProvider: String,
    val asrModel: String,
    val recordedAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)
