package com.xtrust.standalone.vad

data class VadFrameResult(
    val isSpeechDetected: Boolean,
    val rmsDb: Double,
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val speechDurationMs: Long
)

interface LocalVadEngine {
    fun processFrame(samples: ShortArray): VadFrameResult
    fun reset()
}
