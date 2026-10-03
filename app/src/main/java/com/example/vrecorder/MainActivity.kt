package com.example.vrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
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
    private val colorNeonBlue = 0xFF00BFFF.toInt()

    // Launcher for Multiple Permissions (Mic + Storage)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        }

        if (micGranted && storageGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Mic and Storage permissions are required", Toast.LENGTH_LONG).show()
            updateUIState(State.READY)
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
            if (hasAllPermissions()) {
                startRecording()
            } else {
                requestPermissions()
            }
        }

        binding.stopButton.setOnClickListener { stopRecording() }
        
        updateUIState(State.READY)
    }

    private fun hasAllPermissions(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return micGranted && storageGranted
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun startRecording() {
        try {
            tempAudioFile = File.createTempFile("vrecorder_temp", ".m4a", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1) 
                setAudioSamplingRate(44100) 
                setAudioEncodingBitRate(128000) 
                setOutputFile(tempAudioFile!!.absolutePath)
                prepare()
                start()
            }
            updateUIState(State.RECORDING)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
            updateUIState(State.READY)
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { 
                stop()
                reset() 
                release() 
            }
            mediaRecorder = null
            
            // Automatically trigger save
            updateUIState(State.SAVING)
            saveRecording()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
            updateUIState(State.READY)
        }
    }

    private fun saveRecording() {
        val file = tempAudioFile ?: return

        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Recording is empty", Toast.LENGTH_SHORT).show()
            updateUIState(State.READY)
            return
        }

        Thread {
            try {
                Thread.sleep(200)

                val baseDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: filesDir
                val dir = File(baseDir, "VRecorder")

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val finalFile = File(dir, "VRecorder_$timestamp.m4a")

                file.inputStream().channel.use { inputChannel ->
                    finalFile.outputStream().channel.use { outputChannel ->
                        inputChannel.transferTo(0, inputChannel.size(), outputChannel)
                    }
                }

                if (finalFile.exists() && finalFile.length() > 0) {
                    file.delete()
                    tempAudioFile = null
                    
                    runOnUiThread {
                        binding.savedPathTextView.text = "Saved: ${finalFile.absolutePath}"
                        Toast.makeText(this, "Audio auto-saved successfully!", Toast.LENGTH_LONG).show()
                        updateUIState(State.READY)
                    }
                } else {
                    throw Exception("File copy failed")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    updateUIState(State.READY)
                }
            }
        }.start()
    }

    private enum class State { READY, RECORDING, SAVING }

    private fun updateUIState(state: State) {
        when (state) {
            State.READY -> {
                binding.statusTextView.text = "Status: Ready"
                binding.statusTextView.setTextColor(colorWhite)
                binding.recordButton.isEnabled = true
                binding.recordButton.text = "Start Recording"
                binding.stopButton.isEnabled = false
            }
            State.RECORDING -> {
                binding.statusTextView.text = "Status: Recording... Read your story now."
                binding.statusTextView.setTextColor(colorRed)
                binding.recordButton.isEnabled = false
                binding.stopButton.isEnabled = true
            }
            State.SAVING -> {
                binding.statusTextView.text = "Status: Saving audio..."
                binding.statusTextView.setTextColor(colorNeonBlue)
                binding.recordButton.isEnabled = false
                binding.stopButton.isEnabled = false
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
