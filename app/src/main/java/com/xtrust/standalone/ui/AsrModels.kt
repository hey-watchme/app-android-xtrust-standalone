package com.xtrust.standalone.ui

data class AsrDebugState(
    val isReady: Boolean = false,
    val modelDirPath: String = "",
    val isLoadingModel: Boolean = false,
    val lastTranscriptionMs: Long = 0,
    val lastRealTimeFactor: Double = 0.0,
    val modelAccessSummary: String = ""
)
