package com.example.talkingface

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var etSpeechText: EditText
    private lateinit var etOverlayText: EditText
    private lateinit var btnGenerate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private var tts: OfflineTts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSpeechText = findViewById(R.id.etSpeechText)
        etOverlayText = findViewById(R.id.etOverlayText)
        btnGenerate = findViewById(R.id.btnGenerate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        btnGenerate.setOnClickListener {
            val speechText = etSpeechText.text.toString().trim()
            val overlayText = etOverlayText.text.toString().trim()

            if (speechText.isEmpty() || overlayText.isEmpty()) {
                Toast.makeText(this, "Please enter both speech and overlay text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkPermissions()) {
                startGeneration(speechText, overlayText)
            } else {
                requestPermissions()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val speechText = etSpeechText.text.toString().trim()
            val overlayText = etOverlayText.text.toString().trim()
            if (speechText.isNotEmpty() && overlayText.isNotEmpty()) startGeneration(speechText, overlayText)
        } else {
            Toast.makeText(this, "Permissions required to save video", Toast.LENGTH_LONG).show()
        }
    }

    private fun startGeneration(speechText: String, overlayText: String) {
        btnGenerate.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text = "Preparing assets..."

        Thread {
            try {
                copyAssetsToInternalStorage()
                
                runOnUiThread { tvStatus.text = "Initializing Piper TTS..." }
                initializePiperTts()

                runOnUiThread { tvStatus.text = "Generating speech with Piper..." }
                val ttsFile = File(filesDir, "piper_output.wav")
                tts?.generate(text = speechText, sid = 0, speed = 1.0f)?.save(ttsFile.absolutePath)

                runOnUiThread { tvStatus.text = "Rendering video (this may take a minute)..." }
                renderVideo(overlayText, ttsFile)

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "Error: ${e.message}"
                    btnGenerate.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
                tts?.release()
            }
        }.start()
    }

    private fun copyAssetsToInternalStorage() {
        val filesDir = this.filesDir
        val assetsToCopy = listOf("en_US-ljspeech-medium.onnx", "tokens.txt", "talkingface.mp4", "font.ttf")
        for (fileName in assetsToCopy) {
            val dest = File(filesDir, fileName)
            if (!dest.exists()) {
                assets.open(fileName).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
        }
        copyAssetFolder("espeak-ng-data", File(filesDir, "espeak-ng-data"))
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val subPath = "$assetPath/$entry"
            val subEntries = assets.list(subPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                copyAssetFolder(subPath, File(destDir, entry))
            } else {
                val destFile = File(destDir, entry)
                if (!destFile.exists()) {
                    assets.open(subPath).use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun initializePiperTts() {
        val filesDir = this.filesDir
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(filesDir, "en_US-ljspeech-medium.onnx").absolutePath,
                    tokens = File(filesDir, "tokens.txt").absolutePath,
                    dataDir = filesDir.absolutePath, // Must point to folder containing espeak-ng-data
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        tts = OfflineTts(config = config)
    }

    private fun renderVideo(overlayText: String, ttsFile: File) {
        val videoFile = File(filesDir, "talkingface.mp4")
        val fontFile = File(filesDir, "font.ttf")
        val tempOutputFile = File(filesDir, "temp_output.mp4")

        val wrappedOverlayText = wrapText(overlayText, 35).replace("'", "\\\\'")

        val ffmpegCommand = "-y -stream_loop -1 -i ${videoFile.absolutePath} -i ${ttsFile.absolutePath} " +
                "-filter_complex \"[0:v]scale=1080:1920,drawtext=text='$wrappedOverlayText':fontfile=${fontFile.absolutePath}:fontsize=40:fontcolor=white:x=(W-w)/2:y=H-h-300:w=680:h=1320:box=1:boxcolor=black@0.6:boxborderw=10[v]\" " +
                "-map \"[v]\" -map 1:a -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest ${tempOutputFile.absolutePath}"

        FFmpegKit.executeAsync(ffmpegCommand, { session ->
            runOnUiThread {
                progressBar.visibility = ProgressBar.GONE
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToMediaStore(tempOutputFile)
                    tvStatus.text = "Success! Saved to Movies/TalkingFace_Output.mp4"
                    Toast.makeText(this, "Video generated with Piper TTS!", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "FFmpeg failed: ${session.failStackTrace}"
                }
                btnGenerate.isEnabled = true
                tts?.release()
            }
        }, { _ -> }, { stat ->
            runOnUiThread { tvStatus.text = "Rendering... ${stat.time}" }
        })
    }

    private fun saveToMediaStore(file: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "TalkingFace_Output.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it).use { outputStream ->
                FileInputStream(file).use { inputStream -> inputStream.copyTo(outputStream!!) }
            }
        }
    }

    private fun wrapText(text: String, maxCharsPerLine: Int): String {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            if ((currentLine + word).length <= maxCharsPerLine) {
                currentLine += (if (currentLine.isEmpty()) "" else " ") + word
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines.joinToString("\\\\n")
    }
}
