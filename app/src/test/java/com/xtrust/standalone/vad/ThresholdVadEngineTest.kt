package com.xtrust.standalone.vad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ThresholdVadEngineTest {

    @Test
    fun startsSpeechAfterConfiguredFrames() {
        val vad = ThresholdVadEngine(
            minSpeechRatio = 1.1f,
            minHoldRatio = 1.0f,
            minSpeechRms = 300f,
            minHoldRms = 250f,
            startConfirmationFrames = 3,
            silenceSplitFrames = 4
        )
        val loudFrame = sineWaveFrame(amplitude = 12_000)

        val first = vad.processFrame(loudFrame)
        val second = vad.processFrame(loudFrame)
        val third = vad.processFrame(loudFrame)

        assertFalse(first.speechStarted)
        assertFalse(second.speechStarted)
        assertTrue(third.speechStarted)
        assertTrue(third.isSpeechDetected)
    }

    @Test
    fun endsSpeechAfterConfiguredSilence() {
        val vad = ThresholdVadEngine(
            minSpeechRatio = 1.1f,
            minHoldRatio = 1.0f,
            minSpeechRms = 300f,
            minHoldRms = 250f,
            startConfirmationFrames = 2,
            silenceSplitFrames = 3
        )
        val loudFrame = sineWaveFrame(amplitude = 12_000)
        val quietFrame = ShortArray(320)

        vad.processFrame(loudFrame)
        val started = vad.processFrame(loudFrame)
        val duringSilence1 = vad.processFrame(quietFrame)
        val duringSilence2 = vad.processFrame(quietFrame)
        val ended = vad.processFrame(quietFrame)

        assertTrue(started.speechStarted)
        assertTrue(duringSilence1.isSpeechDetected)
        assertTrue(duringSilence2.isSpeechDetected)
        assertTrue(ended.speechEnded)
        assertFalse(ended.isSpeechDetected)
    }

    @Test
    fun keepsSpeechActiveAcrossLowerHoldThreshold() {
        val vad = ThresholdVadEngine(
            minSpeechRatio = 2.0f,
            minHoldRatio = 1.1f,
            minSpeechRms = 700f,
            minHoldRms = 250f,
            startConfirmationFrames = 2,
            silenceSplitFrames = 3
        )
        val loudFrame = sineWaveFrame(amplitude = 12_000)
        val mediumFrame = sineWaveFrame(amplitude = 3_000)

        vad.processFrame(loudFrame)
        val started = vad.processFrame(loudFrame)
        val held = vad.processFrame(mediumFrame)

        assertTrue(started.speechStarted)
        assertTrue(held.isSpeechDetected)
        assertFalse(held.speechEnded)
    }

    private fun sineWaveFrame(
        amplitude: Int,
        sampleCount: Int = 320,
        frequencyHz: Double = 440.0,
        sampleRate: Int = 16_000
    ): ShortArray {
        return ShortArray(sampleCount) { index ->
            val angle = 2.0 * Math.PI * frequencyHz * index / sampleRate.toDouble()
            (sin(angle) * amplitude).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
