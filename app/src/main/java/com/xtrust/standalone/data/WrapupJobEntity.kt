package com.xtrust.standalone.data

data class WrapupJobEntity(
    val id: Long = 0,
    val sessionId: Long,
    val status: String,
    val currentStep: String?,
    val stepDetail: String?,
    val attempts: Int = 0,
    val lastError: String?,
    val llmModel: String?,
    val enqueuedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELED = "canceled"
    }
}
