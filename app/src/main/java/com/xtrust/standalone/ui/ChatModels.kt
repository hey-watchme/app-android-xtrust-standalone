package com.xtrust.standalone.ui

enum class ChatRole {
    User,
    Assistant
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class MemorySnapshot(
    val deviceTotalMb: Long = 0,
    val deviceUsedMb: Long = 0,
    val deviceAvailableMb: Long = 0,
    val appHeapUsedMb: Long = 0,
    val appHeapMaxMb: Long = 0,
    val nativeHeapMb: Long = 0,
    val lowMemory: Boolean = false
)
