package com.xtrust.standalone.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SherpaOnnxAsrEngine : LocalAsrEngine {

    private var recognizer: OfflineRecognizer? = null

    override val isReady: Boolean
        get() = recognizer != null

    override suspend fun initialize(modelDirPath: String) = withContext(Dispatchers.IO) {
        val modelDir = File(modelDirPath)
        val modelFile = File(modelDir, MODEL_FILE_NAME)
        val tokensFile = File(modelDir, TOKENS_FILE_NAME)
        require(modelFile.exists()) { "ASR model file not found: ${modelFile.absolutePath}" }
        require(tokensFile.exists()) { "ASR tokens file not found: ${tokensFile.absolutePath}" }

        recognizer?.release()

        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = modelFile.absolutePath,
                language = "ja",
                useInverseTextNormalization = true
            ),
            tokens = tokensFile.absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelingUnit = "cjkchar"
        )

        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig
        )

        recognizer = OfflineRecognizer(
            assetManager = null,
            config = config
        )
    }

    override suspend fun transcribe(wavePath: String): AsrTranscript = withContext(Dispatchers.IO) {
        val offlineRecognizer = checkNotNull(recognizer) { "ASR engine not initialized" }
        val waveData = WaveReader.readWave(wavePath)
        val stream = offlineRecognizer.createStream()
        try {
            stream.acceptWaveform(waveData.samples, waveData.sampleRate)
            offlineRecognizer.decode(stream)
            val result = offlineRecognizer.getResult(stream)
            AsrTranscript(
                text = result.text.trim(),
                sampleRate = waveData.sampleRate,
                sampleCount = waveData.samples.size
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
        private const val SAMPLE_RATE = 16_000
    }
}
