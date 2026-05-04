package com.xtrust.standalone.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.xtrust.standalone.vad.LocalVadEngine
import com.xtrust.standalone.vad.VadFrameResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MicrophoneVadMonitor(
    private val vadEngine: LocalVadEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val sampleRate = 16_000
    private val frameSize = 320
    private var monitorJob: Job? = null
    private var audioRecord: AudioRecord? = null

    suspend fun start(
        scope: CoroutineScope,
        onFrame: (ShortArray, VadFrameResult) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        if (monitorJob?.isActive == true) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize > 0) { "AudioRecord buffer initialization failed" }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, frameSize * 4)
            )
        } catch (e: SecurityException) {
            // RECORD_AUDIO permission can be revoked; surface this to the caller.
            onError(e)
            return
        }
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord is not initialized"
        }

        vadEngine.reset()
        audioRecord = recorder
        try {
            recorder.startRecording()
        } catch (e: SecurityException) {
            onError(e)
            recorder.release()
            audioRecord = null
            return
        }

        monitorJob = scope.launch(dispatcher) {
            val frameBuffer = ShortArray(frameSize)
            try {
                while (isActive) {
                    val readCount = recorder.read(frameBuffer, 0, frameBuffer.size, AudioRecord.READ_BLOCKING)
                    if (readCount <= 0) continue
                    val frame = if (readCount == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(readCount)
                    }
                    val result = vadEngine.processFrame(frame)
                    onFrame(frame, result)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError(t)
            }
        }
    }

    suspend fun stop() {
        monitorJob?.cancelAndJoin()
        monitorJob = null
        withContext(dispatcher) {
            audioRecord?.run {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            audioRecord = null
            vadEngine.reset()
        }
    }
}
