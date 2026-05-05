package com.xtrust.standalone.data

data class SessionSummaryEntity(
    val sessionId: Long,
    val generatedTitle: String?,
    val theme: String?,
    val agendaJson: String?,
    val llmProvider: String?,
    val llmModel: String?,
    val generatedAt: Long
)
