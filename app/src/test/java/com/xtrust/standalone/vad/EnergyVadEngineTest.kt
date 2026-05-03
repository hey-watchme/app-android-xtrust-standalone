package com.xtrust.standalone.vad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EnergyVadEngineTest {

    @Test
    fun startsSpeechAfterConsecutiveLoudFrames() {
        val vad = EnergyVadEngine(startThresholdDb = -45.0, minSpeechFrames = 3, endSilenceFrames = 4)
        val loudFrame = sineWaveFrame(amplitude = 12_000)

        val first = vad.processFrame(loudFrame)
        val second = vad.processFrame(loudFrame)
        val third = vad.processFrame(loudFrame)

        assertFalse(first.isSpeechDetected)
        assertFalse(second.isSpeechDetected)
        assertTrue(third.speechStarted)
        assertTrue(third.isSpeechDetected)
    }

    @Test
    fun endsSpeechAfterConfiguredSilence() {
        val vad = EnergyVadEngine(startThresholdDb = -45.0, minSpeechFrames = 2, endSilenceFrames = 3)
        val loudFrame = sineWaveFrame(amplitude = 12_000)
        val quietFrame = ShortArray(320)

        vad.processFrame(loudFrame)
        vad.processFrame(loudFrame)
        val silence1 = vad.processFrame(quietFrame)
        val silence2 = vad.processFrame(quietFrame)
        val silence3 = vad.processFrame(quietFrame)

        assertTrue(silence1.isSpeechDetected)
        assertTrue(silence2.isSpeechDetected)
        assertTrue(silence3.speechEnded)
        assertFalse(silence3.isSpeechDetected)
    }

    private fun sineWaveFrame(amplitude: Int, size: Int = 320): ShortArray {
        return ShortArray(size) { index ->
            (sin(index.toDouble() / 10.0) * amplitude).toInt().toShort()
        }
    }
}
