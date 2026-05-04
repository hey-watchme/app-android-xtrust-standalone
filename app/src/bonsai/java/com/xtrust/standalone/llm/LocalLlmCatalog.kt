package com.xtrust.standalone.llm

import com.xtrust.standalone.bonsai.BonsaiLlmEngine
import java.io.File

object LocalLlmCatalog {
    const val DEFAULT_MODEL_ID = "bonsai-1.7b-q1_0"
    const val RUNTIME_ID = "bonsai"
    const val RUNTIME_DISPLAY_NAME = "Bonsai / llama.cpp"

    fun create(modelBaseDir: File): List<LocalLlmDefinition> {
        return listOf(
            LocalLlmDefinition(
                id = DEFAULT_MODEL_ID,
                displayName = "1-bit Bonsai 1.7B",
                assistantLabel = "Bonsai",
                providerLabel = "PrismML Bonsai",
                runtimeLabel = "llama.cpp (Android)",
                description = "Q1_0 GGUF の軽量チャット実験用ビルドです。Gemma とは別ランタイムとして配備します。",
                modelPath = File(modelBaseDir, "Bonsai-1.7B-Q1_0.gguf").absolutePath,
                minimumFileSizeBytes = 150_000_000L,
                dbProvider = "llama.cpp",
                dbModel = "bonsai-1.7b-q1_0",
                engineFactory = { application -> BonsaiLlmEngine(application) }
            )
        )
    }
}
