package com.xtrust.standalone.data

data class ChatMessageEntity(
    val id: Long = 0,
    val threadId: Long,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val responseMs: Long? = null
)
