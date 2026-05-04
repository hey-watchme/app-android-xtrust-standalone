package com.xtrust.standalone.llm

import com.xtrust.standalone.bonsai.BonsaiLlmEngine
import java.io.File

object LocalLlmCatalog {
    const val BONSAI_8B_MODEL_ID = "bonsai-8b-q1_0_g128"
    const val BONSAI_1_7B_MODEL_ID = "bonsai-1.7b-q1_0"
    const val DEFAULT_MODEL_ID = BONSAI_8B_MODEL_ID
    const val RUNTIME_ID = "bonsai"
    const val RUNTIME_DISPLAY_NAME = "Bonsai / llama.cpp"

    fun create(modelBaseDir: File): List<LocalLlmDefinition> {
        return listOf(
            LocalLlmDefinition(
                id = BONSAI_8B_MODEL_ID,
                displayName = "1-bit Bonsai 8B",
                assistantLabel = "Bonsai",
                providerLabel = "PrismML Bonsai",
                runtimeLabel = "llama.cpp (Android)",
                description = "品質優先の 8B GGUF ビルドです。1.7B より重い代わりに、会話品質の再検証に向きます。",
                modelPath = File(modelBaseDir, "Bonsai-8B.gguf").absolutePath,
                minimumFileSizeBytes = 1_000_000_000L,
                dbProvider = "llama.cpp",
                dbModel = "bonsai-8b-q1_0_g128",
                engineFactory = { application -> BonsaiLlmEngine(application) }
            ),
            LocalLlmDefinition(
                id = BONSAI_1_7B_MODEL_ID,
                displayName = "1-bit Bonsai 1.7B",
                assistantLabel = "Bonsai",
                providerLabel = "PrismML Bonsai",
                runtimeLabel = "llama.cpp (Android)",
                description = "軽量優先の Q1_0 GGUF ビルドです。8B と体感速度や品質を比較したい時の予備候補です。",
                modelPath = File(modelBaseDir, "Bonsai-1.7B-Q1_0.gguf").absolutePath,
                minimumFileSizeBytes = 150_000_000L,
                dbProvider = "llama.cpp",
                dbModel = "bonsai-1.7b-q1_0",
                engineFactory = { application -> BonsaiLlmEngine(application) }
            )
        )
    }
}
