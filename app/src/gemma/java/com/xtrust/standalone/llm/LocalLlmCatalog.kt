package com.xtrust.standalone.llm

import java.io.File

object LocalLlmCatalog {
    const val DEFAULT_MODEL_ID = "gemma4-e2b"
    const val RUNTIME_ID = "gemma"
    const val RUNTIME_DISPLAY_NAME = "Gemma / LiteRT-LM"

    fun create(modelBaseDir: File): List<LocalLlmDefinition> {
        return listOf(
            LocalLlmDefinition(
                id = DEFAULT_MODEL_ID,
                displayName = "Gemma 4 E2B",
                assistantLabel = "Gemma",
                providerLabel = "Google Gemma",
                runtimeLabel = "LiteRT-LM",
                description = "現行の基準モデルです。ローカル会話と要約の比較元として使います。",
                modelPath = File(modelBaseDir, "gemma-4-E2B-it.litertlm").absolutePath,
                minimumFileSizeBytes = 1_000_000_000L,
                dbProvider = "litert-lm",
                dbModel = "gemma4-e2b",
                engineFactory = { LiteRtLlmEngine() }
            )
        )
    }
}
