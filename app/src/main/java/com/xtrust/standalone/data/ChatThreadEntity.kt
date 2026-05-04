package com.xtrust.standalone.data

data class ChatThreadEntity(
    val id: Long = 0,
    val title: String,
    val llmModel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
