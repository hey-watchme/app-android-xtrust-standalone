package com.xtrust.standalone.vad

import kotlin.math.log10
import kotlin.math.sqrt

class EnergyVadEngine(
    private val sampleRate: Int = 16_000,
    private val startThresholdDb: Double = -40.0,
    private val continueThresholdDb: Double = -48.0,
    private val minSpeechFrames: Int = 4,
    private val endSilenceFrames: Int = 24
) : LocalVadEngine {

    private var isSpeechActive = false
    private var speechFrames = 0
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0

    override fun processFrame(samples: ShortArray): VadFrameResult {
        val rmsDb = calculateRmsDb(samples)
        var speechStarted = false
        var speechEnded = false
        val thresholdDb = if (isSpeechActive) continueThresholdDb else startThresholdDb

        if (rmsDb >= thresholdDb) {
            consecutiveSpeechFrames += 1
            consecutiveSilenceFrames = 0
        } else {
            consecutiveSilenceFrames += 1
            consecutiveSpeechFrames = 0
        }

        if (!isSpeechActive && consecutiveSpeechFrames >= minSpeechFrames) {
            isSpeechActive = true
            speechStarted = true
            speechFrames = consecutiveSpeechFrames
        } else if (isSpeechActive) {
            speechFrames += 1
            if (consecutiveSilenceFrames >= endSilenceFrames) {
                isSpeechActive = false
                speechEnded = true
            }
        }

        val speechDurationMs = if (speechFrames == 0) {
            0L
        } else {
            speechFrames * samples.size * 1000L / sampleRate
        }

        if (speechEnded) {
            speechFrames = 0
        }

        return VadFrameResult(
            isSpeechDetected = isSpeechActive,
            rmsDb = rmsDb,
            speechStarted = speechStarted,
            speechEnded = speechEnded,
            speechDurationMs = speechDurationMs
        )
    }

    override fun reset() {
        isSpeechActive = false
        speechFrames = 0
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }

    private fun calculateRmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return -120.0
        var squareSum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            squareSum += normalized * normalized
        }
        val rms = sqrt(squareSum / samples.size)
        if (rms <= 1e-9) return -120.0
        return 20.0 * log10(rms)
    }
}
