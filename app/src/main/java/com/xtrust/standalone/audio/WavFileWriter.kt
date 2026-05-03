package com.xtrust.standalone.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFileWriter {

    fun writeMono16BitPcm(
        outputFile: File,
        samples: ShortArray,
        sampleRate: Int
    ) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { output ->
            val pcmBytes = samples.size * 2
            val byteRate = sampleRate * 2

            output.write("RIFF".toByteArray())
            output.write(intToLittleEndian(36 + pcmBytes))
            output.write("WAVE".toByteArray())
            output.write("fmt ".toByteArray())
            output.write(intToLittleEndian(16))
            output.write(shortToLittleEndian(1))
            output.write(shortToLittleEndian(1))
            output.write(intToLittleEndian(sampleRate))
            output.write(intToLittleEndian(byteRate))
            output.write(shortToLittleEndian(2))
            output.write(shortToLittleEndian(16))
            output.write("data".toByteArray())
            output.write(intToLittleEndian(pcmBytes))
            output.write(shortArrayToLittleEndian(samples))
        }
    }

    private fun shortArrayToLittleEndian(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }
}
