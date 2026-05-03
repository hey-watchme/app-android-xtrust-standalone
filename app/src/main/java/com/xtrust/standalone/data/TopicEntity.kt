package com.xtrust.standalone.data

data class TopicEntity(
    val id: Long = 0,
    val title: String,
    val summary: String,
    val startAt: Long,
    val endAt: Long,
    val llmProvider: String,
    val llmModel: String,
    val createdAt: Long = System.currentTimeMillis()
)
