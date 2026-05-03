package com.xtrust.standalone.asr

data class AsrTranscript(
    val text: String,
    val sampleRate: Int,
    val sampleCount: Int
)

interface LocalAsrEngine {
    suspend fun initialize(modelDirPath: String)
    suspend fun transcribe(wavePath: String): AsrTranscript
    fun close()
    val isReady: Boolean
}
