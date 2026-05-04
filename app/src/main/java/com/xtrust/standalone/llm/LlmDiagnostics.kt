package com.xtrust.standalone.llm

interface LlmDiagnostics {
    suspend fun runtimeInfo(): String
    suspend fun benchmark(): String
}
