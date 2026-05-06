package com.xtrust.standalone.ui

data class VadDebugState(
    val engineLabel: String = "Threshold VAD",
    val speechStartMs: Int = 60,
    val silenceSplitMs: Int = 2400,
    val isEngineReady: Boolean = false,
    val engineStatus: String = "未初期化",
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val rmsDb: Double = -120.0,
    val detectedSegments: Int = 0,
    val lastSpeechDurationMs: Long = 0,
    val savedSegments: List<AudioSegment> = emptyList()
)
