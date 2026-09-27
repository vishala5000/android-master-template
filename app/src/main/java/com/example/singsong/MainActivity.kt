package com.example.singsong

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            tvStatus.text = "Permissions granted. Ready to record!"
        } else {
            tvStatus.text = "Permissions denied. App cannot record audio."
            Toast.makeText(this, "Audio permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)
        progressBar = findViewById(R.id.progressBar)

        checkPermissions()

        btnRecord.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecordingAndProcess() }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            tvStatus.text = "Ready to record your singing!"
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startRecording() {
        tvStatus.text = "Recording... Sing your heart out!"
        btnRecord.isEnabled = false
        btnStop.isEnabled = true
        isRecording = true

        recordingThread = Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            
            val tempFile = File(cacheDir, "temp_recording.pcm")
            audioRecord?.startRecording()
            
            tempFile.outputStream().use { outputStream ->
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        byteBuffer.asShortBuffer().put(buffer, 0, read)
                        outputStream.write(byteBuffer.array(), 0, read * 2)
                    }
                }
            }
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
        recordingThread?.start()
    }

    private fun stopRecordingAndProcess() {
        isRecording = false
        btnRecord.isEnabled = false
        btnStop.isEnabled = false
        tvStatus.text = "Processing: Cinematic Studio Mix..."
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 10

        Thread {
            try {
                val tempFile = File(cacheDir, "temp_recording.pcm")
                val pcmBytes = tempFile.readBytes()
                val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcmData = ShortArray(shortBuffer.remaining())
                shortBuffer.get(pcmData)
                
                if (pcmData.isEmpty()) {
                    runOnUiThread { Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                
                runOnUiThread { progressBar.progress = 30 }
                val processedVocal = applyCinematicVocalProcessing(pcmData)
                
                runOnUiThread { progressBar.progress = 60 }
                val backingTrack = generateCinematicBackingTrack(pcmData, 44100)
                
                runOnUiThread { progressBar.progress = 80 }
                val finalMix = mixTracks(processedVocal, backingTrack)
                
                runOnUiThread { progressBar.progress = 90 }
                val fileName = "Singing_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.wav"
                val wavFile = File(cacheDir, fileName)
                saveAsWav(wavFile, finalMix, 44100)
                
                saveToSharedStorage(wavFile, fileName)
                
                tempFile.delete()
                wavFile.delete()
                
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Done! Saved to Music/Singing folder."
                    Toast.makeText(this, "Cinematic studio mix saved!", Toast.LENGTH_LONG).show()
                    btnRecord.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    tvStatus.text = "Ready to record your singing!"
                    btnRecord.isEnabled = true
                }
            }
        }.start()
    }

    // --- DSP ENGINE: CINEMATIC VOCAL PROCESSING ---
    private fun applyCinematicVocalProcessing(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val sampleRate = 44100f
        val output = FloatArray(input.size)
        
        // Normalize
        var maxVal = 0f
        for (i in input.indices) {
            val absVal = abs(input[i].toFloat())
            if (absVal > maxVal) maxVal = absVal
        }
        val normFactor = if (maxVal > 0f) (Short.MAX_VALUE.toFloat() / maxVal) * 0.9f else 1f
        
        // Gentle low-pass to remove harshness
        var lpState = 0f
        val alpha = 0.18f
        
        // Lush cinematic reverb (longer, smoother tail)
        val delaySamples = (0.08f * sampleRate).toInt() // 80ms for spacious feel
        val delayBuffer = FloatArray(delaySamples)
        var delayPtr = 0
        
        for (i in input.indices) {
            var s = (input[i].toFloat() * normFactor) / Short.MAX_VALUE
            lpState += alpha * (s - lpState)
            s = lpState
            
            val delayed = delayBuffer[delayPtr]
            val filteredFeedback = delayed * 0.55f // Longer, warmer tail
            delayBuffer[delayPtr] = s + filteredFeedback
            
            // 70% dry, 30% wet for cinematic blend
            output[i] = (s * 0.70f) + (delayed * 0.30f)
            delayPtr = (delayPtr + 1) % delaySamples
        }
        
        // Soft compression
        val threshold = 0.65f
        val ratio = 2.5f
        for (i in output.indices) {
            var sample = output[i]
            if (abs(sample) > threshold) {
                sample = threshold + (abs(sample) - threshold) / ratio
                if (output[i] < 0) sample = -sample
            }
            output[i] = sample
        }
        
        val result = ShortArray(output.size)
        for (i in output.indices) {
            val clamped = max(-1f, min(1f, output[i]))
            result[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    // --- DSP ENGINE: STABLE KEY DETECTION ---
    private fun detectStableKey(data: ShortArray, sampleRate: Int): Float {
        var maxAmp = 0f
        var bestStart = 0
        val blockSize = 88200 // 2 seconds for most stable detection
        
        for (i in 0 until data.size - blockSize step blockSize) {
            var amp = 0f
            for (j in 0 until blockSize) amp += abs(data[i + j].toFloat())
            if (amp > maxAmp) { maxAmp = amp; bestStart = i }
        }
        
        if (maxAmp < 100000f) return 220f // Default A3
        
        val block = FloatArray(blockSize)
        for (i in 0 until blockSize) block[i] = data[bestStart + i].toFloat()
        
        var bestCorrelation = -1f
        var bestPeriod = -1
        val minPeriod = sampleRate / 500
        val maxPeriod = sampleRate / 80
        
        for (period in minPeriod until maxPeriod) {
            var correlation = 0f
            for (i in 0 until blockSize - period) correlation += block[i] * block[i + period]
            if (correlation > bestCorrelation) { bestCorrelation = correlation; bestPeriod = period }
        }
        
        val rawFreq = if (bestPeriod > 0) sampleRate.toFloat() / bestPeriod else 220f
        
        // Quantize to nearest perfect note
        val midi = 69f + 12f * (ln(rawFreq / 440.0f) / ln(2.0f))
        val roundedMidi = midi.roundToInt()
        return 440f * 2.0.pow((roundedMidi - 69) / 12.0).toFloat()
    }

    // --- DSP ENGINE: CINEMATIC BACKING TRACK ---
    private fun generateCinematicBackingTrack(data: ShortArray, sampleRate: Int): ShortArray {
        val length = data.size
        val output = FloatArray(length)
        
        // Detect key ONCE from the most stable part
        val rootFreq = detectStableKey(data, sampleRate)
        
        // Cinematic chord progression: I - IV - V - I (slow movement)
        val progression = arrayOf(
            intArrayOf(0, 7, 12),   // I (Root, 5th, Octave)
            intArrayOf(5, 12, 17),  // IV
            intArrayOf(7, 14, 19),  // V
            intArrayOf(0, 7, 12)    // I
        )
        
        val chordDuration = 4.0f // Each chord lasts 4 seconds
        val chordSamples = (chordDuration * sampleRate).toInt()
        
        // Phase-continuous oscillators for each note
        val maxNotes = 3
        val phases = Array(maxNotes) { FloatArray(maxNotes) { 0f } }
        var currentChord = 0
        var chordStartSample = 0
        
        for (i in 0 until length) {
            // Change chord every 4 seconds
            if (i - chordStartSample >= chordSamples) {
                chordStartSample = i
                currentChord = (currentChord + 1) % progression.size
            }
            
            val chord = progression[currentChord]
            val timeInChord = (i - chordStartSample).toFloat() / sampleRate
            
            // Smooth envelope for chord transitions (no clicking)
            val fadeIn = min(1f, timeInChord / 0.5f) // 0.5s fade in
            val fadeOut = min(1f, (chordDuration - timeInChord) / 0.5f) // 0.5s fade out
            val envelope = fadeIn * fadeOut
            
            var sample = 0f
            
            // Generate warm string pad (additive synthesis with harmonics)
            for (n in chord.indices) {
                val semitone = chord[n]
                val freq = (rootFreq * 2.0.pow(semitone.toDouble() / 12.0)).toFloat()
                
                // Fundamental + harmonics for rich string sound
                var noteSample = sin(phases[n][0]).toFloat()
                noteSample += 0.5f * sin(phases[n][1]).toFloat()
                noteSample += 0.25f * sin(phases[n][2]).toFloat()
                
                sample += noteSample * 0.3f
                
                // Advance phases (phase-continuous)
                phases[n][0] = (phases[n][0] + 2.0f * PI.toFloat() * freq / sampleRate) % (2.0f * PI.toFloat())
                phases[n][1] = (phases[n][1] + 2.0f * PI.toFloat() * freq * 2 / sampleRate) % (2.0f * PI.toFloat())
                phases[n][2] = (phases[n][2] + 2.0f * PI.toFloat() * freq * 3 / sampleRate) % (2.0f * PI.toFloat())
            }
            
            output[i] = sample * envelope * 0.15f // Very subtle backing (15% volume)
        }
        
        val result = ShortArray(length)
        for (i in output.indices) {
            val clamped = max(-1f, min(1f, output[i]))
            result[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    // --- DSP ENGINE: MIXING ---
    private fun mixTracks(vocal: ShortArray, backing: ShortArray): ShortArray {
        val result = ShortArray(vocal.size)
        for (i in vocal.indices) {
            // Vocal 85%, Backing 15% - voice is always front and center
            val mixed = (vocal[i].toFloat() * 0.85f) + (backing[i].toFloat() * 0.15f)
            val clamped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), mixed))
            result[i] = clamped.toInt().toShort()
        }
        return result
    }

    // --- FILE ENCODING ---
    private fun saveAsWav(file: File, pcmData: ShortArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size * 2

        val header = ByteArray(44)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        writeInt(header, 4, 36 + dataSize)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        writeInt(header, 16, 16)
        writeShort(header, 20, 1.toShort())
        writeShort(header, 22, channels.toShort())
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, blockAlign.toShort())
        writeShort(header, 34, bitsPerSample.toShort())
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        writeInt(header, 40, dataSize)

        file.outputStream().use { fos ->
            fos.write(header)
            val buffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(pcmData)
            fos.write(buffer.array())
        }
    }

    private fun writeInt(header: ByteArray, offset: Int, value: Int) {
        header[offset] = (value and 0xff).toByte()
        header[offset + 1] = ((value shr 8) and 0xff).toByte()
        header[offset + 2] = ((value shr 16) and 0xff).toByte()
        header[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(header: ByteArray, offset: Int, value: Short) {
        header[offset] = (value.toInt() and 0xff).toByte()
        header[offset + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }

    private fun saveToSharedStorage(wavFile: File, fileName: String) {
        try {
            val resolver = contentResolver
            val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val newAudioDetails = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Singing") 
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val newAudioUri = resolver.insert(audioCollection, newAudioDetails)
            
            newAudioUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { outputStream ->
                    wavFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                newAudioDetails.clear()
                newAudioDetails.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, newAudioDetails, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
    }
}
