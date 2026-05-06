package com.xtrust.standalone.vad

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class ThresholdVadEngine(
    private val sampleRate: Int = 16_000,
    private val frameSizeSamples: Int = 320,
    private val minSpeechRatio: Float = 2.4f,
    private val minHoldRatio: Float = 1.4f,
    private val minSpeechRms: Float = 700f,
    private val minHoldRms: Float = 450f,
    private val minSpeechZcr: Float = 0.02f,
    private val maxSpeechZcr: Float = 0.25f,
    private val minHoldZcr: Float = 0.01f,
    private val maxHoldZcr: Float = 0.30f,
    private val noiseFloorDecay: Float = 0.997f,
    private val startConfirmationFrames: Int = 3,
    private val silenceSplitFrames: Int = 15
) : LocalVadEngine {

    val engineLabel: String = "Threshold VAD"
    private val frameDurationMs: Int = frameSizeSamples * 1000 / sampleRate
    val speechStartMs: Int = frameDurationMs * startConfirmationFrames
    val silenceSplitMs: Int = frameDurationMs * silenceSplitFrames

    private var noiseFloorRms = 200.0f
    private var isSpeechActive = false
    private var activeSpeechFrames = 0
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0

    override fun processFrame(samples: ShortArray): VadFrameResult {
        val frameLength = samples.size.coerceAtMost(frameSizeSamples)
        val rms = computeRms(samples, frameLength).coerceAtLeast(1.0f)
        noiseFloorRms = noiseFloorDecay * noiseFloorRms + (1 - noiseFloorDecay) * rms
        val ratio = rms / max(noiseFloorRms, 1.0f)
        val zcr = computeZcr(samples, frameLength)
        val rmsDb = calculateRmsDb(samples, frameLength)

        val passedGate = if (isSpeechActive) {
            ratio >= minHoldRatio && rms >= minHoldRms && zcr in minHoldZcr..maxHoldZcr
        } else {
            ratio >= minSpeechRatio && rms >= minSpeechRms && zcr in minSpeechZcr..maxSpeechZcr
        }

        var speechStarted = false
        var speechEnded = false

        if (passedGate) {
            consecutiveSpeechFrames += 1
            consecutiveSilenceFrames = 0
        } else {
            consecutiveSilenceFrames += 1
            consecutiveSpeechFrames = 0
        }

        if (!isSpeechActive) {
            if (consecutiveSpeechFrames >= startConfirmationFrames) {
                isSpeechActive = true
                speechStarted = true
                activeSpeechFrames = consecutiveSpeechFrames
            }
        } else {
            activeSpeechFrames += 1
            if (consecutiveSilenceFrames >= silenceSplitFrames) {
                isSpeechActive = false
                speechEnded = true
                consecutiveSpeechFrames = 0
                consecutiveSilenceFrames = 0
            }
        }

        val speechDurationMs = if (isSpeechActive || speechEnded) {
            activeSpeechFrames * frameDurationMs.toLong()
        } else {
            0L
        }

        if (speechEnded) {
            activeSpeechFrames = 0
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
        noiseFloorRms = 200.0f
        isSpeechActive = false
        activeSpeechFrames = 0
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }

    fun isAvailable(): Boolean = true

    fun statusText(): String = "準備完了"

    private fun computeRms(samples: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0f
        for (index in 0 until length) {
            val value = abs(samples[index].toInt()).toFloat()
            sum += value * value
        }
        return sqrt(sum / length)
    }

    private fun computeZcr(samples: ShortArray, length: Int): Float {
        if (length <= 1) return 0f
        var crossings = 0
        var previous = samples[0]
        for (index in 1 until length) {
            val current = samples[index]
            if ((previous >= 0 && current < 0) || (previous < 0 && current >= 0)) {
                crossings += 1
            }
            previous = current
        }
        return crossings.toFloat() / (length - 1)
    }

    private fun calculateRmsDb(samples: ShortArray, length: Int): Double {
        if (length <= 0) return -120.0
        var squareSum = 0.0
        for (index in 0 until length) {
            val normalized = samples[index] / 32768.0
            squareSum += normalized * normalized
        }
        val rms = sqrt(squareSum / length)
        if (rms <= 1e-9) return -120.0
        return 20.0 * log10(rms)
    }
}
