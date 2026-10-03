package com.example.vrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vrecorder.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaRecorder: MediaRecorder? = null
    private var tempAudioFile: File? = null

    private val colorWhite = 0xFFFFFFFF.toInt()
    private val colorRed = 0xFFFF0000.toInt()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.recordButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.stopButton.setOnClickListener { stopRecording() }
        binding.saveButton.setOnClickListener { saveRecording() }
        updateUIState(State.READY)
    }

    private fun startRecording() {
        try {
            tempAudioFile = File.createTempFile("vrecorder_temp", ".m4a", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1) // Mono prevents channel-mapping crashes on some devices
                setAudioSamplingRate(44100) // 44.1kHz is universally supported
                setAudioEncodingBitRate(128000) // 128kbps is safe and high quality for voice
                setOutputFile(tempAudioFile!!.absolutePath)
                prepare()
                start()
            }
            updateUIState(State.RECORDING)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { 
                stop()
                reset() // CRITICAL: Prevents native C++ state machine crashes
                release() 
            }
            mediaRecorder = null
            updateUIState(State.STOPPED)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun saveRecording() {
        val file = tempAudioFile ?: return

        // Prevent crash if file is empty or missing
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Recording is empty", Toast.LENGTH_SHORT).show()
            updateUIState(State.READY)
            return
        }

        // Show loading state
        binding.saveButton.isEnabled = false
        binding.saveButton.text = "Saving..."

        // CRITICAL FIX: Run file operations on a background thread to prevent ANR (App Not Responding) crashes
        Thread {
            try {
                // Small delay to ensure OS releases file lock after MediaRecorder.release()
                Thread.sleep(200)

                val baseDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: filesDir
                val dir = File(baseDir, "VRecorder")

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val finalFile = File(dir, "VRecorder_$timestamp.m4a")

                // Copy file safely using FileChannel
                file.inputStream().channel.use { inputChannel ->
                    finalFile.outputStream().channel.use { outputChannel ->
                        inputChannel.transferTo(0, inputChannel.size(), outputChannel)
                    }
                }

                // Verify copy was successful before deleting temp
                if (finalFile.exists() && finalFile.length() > 0) {
                    file.delete()
                    tempAudioFile = null
                    
                    // Update UI on the main thread
                    runOnUiThread {
                        binding.savedPathTextView.text = "Saved: ${finalFile.absolutePath}"
                        Toast.makeText(this, "Audio saved successfully!", Toast.LENGTH_LONG).show()
                        updateUIState(State.READY)
                    }
                } else {
                    throw Exception("File copy failed")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    updateUIState(State.STOPPED) // Re-enable save button if it failed
                }
            }
        }.start()
    }

    private enum class State { READY, RECORDING, STOPPED }

    private fun updateUIState(state: State) {
        when (state) {
            State.READY -> {
                binding.statusTextView.text = "Status: Ready"
                binding.statusTextView.setTextColor(colorWhite)
                binding.recordButton.isEnabled = true
                binding.stopButton.isEnabled = false
                binding.saveButton.isEnabled = false
                binding.saveButton.text = "Save to Recordings"
            }
            State.RECORDING -> {
                binding.statusTextView.text = "Status: Recording... Read your story now."
                binding.statusTextView.setTextColor(colorRed)
                binding.recordButton.isEnabled = false
                binding.stopButton.isEnabled = true
                binding.saveButton.isEnabled = false
            }
            State.STOPPED -> {
                binding.statusTextView.text = "Status: Recording finished. Ready to save."
                binding.statusTextView.setTextColor(colorWhite)
                binding.recordButton.isEnabled = true
                binding.stopButton.isEnabled = false
                binding.saveButton.isEnabled = true
                binding.saveButton.text = "Save to Recordings"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
        tempAudioFile?.delete()
    }
}
