package com.xtrust.standalone.ui

data class VadDebugState(
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val rmsDb: Double = -120.0,
    val thresholdDb: Double = -40.0,
    val continueThresholdDb: Double = -48.0,
    val pauseSplitMs: Long = 2400,
    val detectedSegments: Int = 0,
    val lastSpeechDurationMs: Long = 0,
    val savedSegments: List<AudioSegment> = emptyList()
)
