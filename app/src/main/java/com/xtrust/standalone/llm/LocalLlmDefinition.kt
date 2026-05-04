package com.xtrust.standalone.llm

data class LocalLlmDefinition(
    val id: String,
    val displayName: String,
    val assistantLabel: String,
    val providerLabel: String,
    val runtimeLabel: String,
    val description: String,
    val modelPath: String,
    val minimumFileSizeBytes: Long,
    val dbProvider: String,
    val dbModel: String,
    val implementationNote: String? = null,
    val engineFactory: ((android.app.Application) -> LocalLlmEngine)? = null
) {
    val isLoadable: Boolean
        get() = engineFactory != null
}
