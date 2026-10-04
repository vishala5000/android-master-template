package com.example.talkingface

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream

class PiperTtsHelper(private val context: Context) {

    private var tts: OfflineTts? = null
    private var isInitialized = false

    /**
     * Copies assets (model, tokens, espeak-ng-data) from APK to internal storage.
     * sherpa-onnx requires filesystem paths, not APK asset paths.
     */
    fun prepareAssets() {
        val filesDir = context.filesDir
        val assetsToCopy = listOf(
            "en_US-ljspeech-medium.onnx",
            "tokens.txt"
        )

        // Copy individual files
        for (fileName in assetsToCopy) {
            val dest = File(filesDir, fileName)
            if (!dest.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
        }

        // Copy espeak-ng-data folder recursively
        copyAssetFolder("espeak-ng-data", File(filesDir, "espeak-ng-data"))
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: return
        for (entry in entries) {
            val subPath = "$assetPath/$entry"
            val subEntries = context.assets.list(subPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                // It's a folder
                copyAssetFolder(subPath, File(destDir, entry))
            } else {
                // It's a file
                val destFile = File(destDir, entry)
                if (!destFile.exists()) {
                    context.assets.open(subPath).use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun initialize() {
        if (isInitialized) return

        prepareAssets()

        val filesDir = context.filesDir
        val modelPath = File(filesDir, "en_US-ljspeech-medium.onnx").absolutePath
        val tokensPath = File(filesDir, "tokens.txt").absolutePath
        // dataDir must be the PARENT folder containing espeak-ng-data/
        val dataDir = filesDir.absolutePath

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = dataDir,
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )

        tts = OfflineTts(config = config)
        isInitialized = true
    }

    /**
     * Generates speech using Piper TTS and saves as WAV.
     * @return the sample rate of the generated audio
     */
    fun generateSpeech(text: String, outputFile: File): Int {
        if (!isInitialized) initialize()
        val audio = tts!!.generate(text = text, sid = 0, speed = 1.0f)
        audio.save(outputFile.absolutePath)
        return audio.sampleRate
    }

    fun release() {
        tts?.release()
        tts = null
        isInitialized = false
    }
}
