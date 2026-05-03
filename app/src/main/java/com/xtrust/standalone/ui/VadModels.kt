package com.xtrust.standalone.ui

data class VadDebugState(
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val rmsDb: Double = -120.0,
    val thresholdDb: Double = -42.0,
    val detectedSegments: Int = 0,
    val lastSpeechDurationMs: Long = 0
)
