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

data class ChatThreadItem(
    val id: Long,
    val title: String,
    val updatedAtLabel: String,
    val isSelected: Boolean
)

data class LlmModelOption(
    val id: String,
    val displayName: String,
    val assistantLabel: String,
    val providerLabel: String,
    val runtimeLabel: String,
    val description: String,
    val modelPath: String,
    val isLoadable: Boolean,
    val implementationNote: String? = null
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
