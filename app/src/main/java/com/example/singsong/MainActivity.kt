package com.example.singsong

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var btnPlay: Button
    private lateinit var progressBar: ProgressBar

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var mediaPlayer: MediaPlayer? = null
    private var lastSavedUri: Uri? = null

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

        btnPlay = Button(this).apply {
            text = "▶ Play My Song"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        rootLayout?.addView(btnPlay)

        checkPermissions()

        btnRecord.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecordingAndProcess() }
        btnPlay.setOnClickListener { playSavedSong() }
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
        btnPlay.isEnabled = false
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
        tvStatus.text = "Processing: Professional Studio Mix..."
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
                
                runOnUiThread { progressBar.progress = 20 }
                val processedVocal = applyProfessionalMix(pcmData, 44100)
                
                runOnUiThread { progressBar.progress = 50 }
                val guitarTrack = generateSupportingGuitar(pcmData, 44100)
                
                runOnUiThread { progressBar.progress = 75 }
                val finalMix = mixPerfectBalance(processedVocal, guitarTrack)
                
                runOnUiThread { progressBar.progress = 90 }
                
                val fileName = "Singing_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.wav"
                val wavFile = File(cacheDir, fileName)
                saveAsWav(wavFile, finalMix, 44100)
                
                val savedUri = saveToSharedStorage(wavFile, fileName)
                lastSavedUri = savedUri
                
                tempFile.delete()
                wavFile.delete()
                
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Done! Tap Play to listen."
                    Toast.makeText(this, "Perfect professional song saved!", Toast.LENGTH_LONG).show()
                    btnRecord.isEnabled = true
                    btnPlay.isEnabled = true
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

    // PROFESSIONAL BALANCED MIX - subtle, polished effects
    private fun applyProfessionalMix(input: ShortArray, sampleRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val output = FloatArray(input.size)
        
        var maxVal = 0f
        for (i in input.indices) {
            val absVal = abs(input[i].toFloat())
            if (absVal > maxVal) maxVal = absVal
        }
        val normFactor = if (maxVal > 0f) (Short.MAX_VALUE.toFloat() / maxVal) * 0.95f else 1f
        
        // STEP 1: GENTLE AUTO-TUNE (30% - natural correction)
        val blockSize = 2048
        val numBlocks = input.size / blockSize
        val corrected = FloatArray(input.size)
        
        for (b in 0 until numBlocks) {
            val start = b * blockSize
            val end = min(input.size, start + blockSize)
            
            val amp = getAmplitude(input, start, blockSize)
            var targetFreq = -1f
            
            if (amp > 1500f) {
                val rawPitch = detectPitch(input, start, blockSize, sampleRate)
                if (rawPitch in 80f..800f) {
                    targetFreq = quantizeToNote(rawPitch)
                }
            }
            
            for (i in start until end) {
                val original = (input[i].toFloat() * normFactor) / Short.MAX_VALUE
                if (targetFreq > 0f) {
                    val t = (i - start).toFloat() / sampleRate
                    // Gentle 30% correction
                    val correctedSample = original * 0.70f + 
                        (sin(2.0 * PI * targetFreq * t) * abs(original) * 0.30f).toFloat()
                    corrected[i] = correctedSample
                } else {
                    corrected[i] = original
                }
            }
        }
        
        // STEP 2: SUBTLE REVERB (20% - smooth hall warmth)
        val reverbTaps = intArrayOf(
            (0.013f * sampleRate).toInt(),
            (0.019f * sampleRate).toInt(),
            (0.029f * sampleRate).toInt(),
            (0.037f * sampleRate).toInt(),
            (0.053f * sampleRate).toInt(),
            (0.071f * sampleRate).toInt()
        )
        
        val reverbBuffers = Array(reverbTaps.size) { FloatArray(reverbTaps[it]) }
        val reverbPtrs = IntArray(reverbTaps.size) { 0 }
        val reverbGains = floatArrayOf(0.5f, 0.4f, 0.3f, 0.25f, 0.2f, 0.15f)
        
        val afterReverb = FloatArray(corrected.size)
        
        for (i in corrected.indices) {
            val s = corrected[i]
            var reverbSum = 0f
            
            for (t in reverbTaps.indices) {
                val delayed = reverbBuffers[t][reverbPtrs[t]]
                reverbSum += delayed * reverbGains[t]
                
                val feedback = s + delayed * 0.30f
                reverbBuffers[t][reverbPtrs[t]] = feedback * 0.80f
                
                reverbPtrs[t] = (reverbPtrs[t] + 1) % reverbTaps[t]
            }
            
            // 80% dry voice, 20% subtle reverb
            afterReverb[i] = (s * 0.80f) + (reverbSum * 0.20f)
        }
        
        // STEP 3: LIGHT ECHO (15% - gentle repetitions)
        val echoDelaySamples = (0.35f * sampleRate).toInt()
        val echoBuffer = FloatArray(echoDelaySamples)
        var echoPtr = 0
        
        for (i in afterReverb.indices) {
            val s = afterReverb[i]
            val delayed = echoBuffer[echoPtr]
            
            // Gentle feedback
            echoBuffer[echoPtr] = s + delayed * 0.25f
            
            // 85% voice, 15% light echo
            output[i] = (s * 0.85f) + (delayed * 0.15f)
            
            echoPtr = (echoPtr + 1) % echoDelaySamples
        }
        
        val result = ShortArray(output.size)
        for (i in output.indices) {
            val clamped = max(-1f, min(1f, output[i]))
            result[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    private fun detectPitch(data: ShortArray, start: Int, len: Int, sampleRate: Int): Float {
        val end = min(data.size, start + len)
        val actualLen = end - start
        if (actualLen < 1024) return -1f
        
        val block = FloatArray(actualLen)
        for (i in 0 until actualLen) block[i] = data[start + i].toFloat()
        
        var bestCorrelation = -1f
        var bestPeriod = -1
        val minPeriod = sampleRate / 1000
        val maxPeriod = sampleRate / 60
        
        for (period in minPeriod until maxPeriod) {
            var correlation = 0f
            for (i in 0 until actualLen - period) {
                correlation += block[i] * block[i + period]
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestPeriod = period
            }
        }
        
        return if (bestPeriod > 0) sampleRate.toFloat() / bestPeriod else -1f
    }

    private fun getAmplitude(data: ShortArray, start: Int, len: Int): Float {
        var sum = 0f
        val end = min(data.size, start + len)
        for (i in start until end) sum += abs(data[i].toFloat())
        return sum / (end - start)
    }

    private fun quantizeToNote(freq: Float): Float {
        if (freq <= 0f) return -1f
        val midi = 69f + 12f * (ln(freq / 440.0) / ln(2.0)).toFloat()
        val roundedMidi = midi.roundToInt()
        val exponent = (roundedMidi - 69) / 12.0
        return 440f * (2.0.pow(exponent)).toFloat()
    }

    // SUPPORTING GUITAR (25% - enhances without competing)
    private fun generateSupportingGuitar(data: ShortArray, sampleRate: Int): ShortArray {
        val length = data.size
        val output = FloatArray(length)
        
        val blockSize = 22050
        val numBlocks = length / blockSize
        val blockPitches = FloatArray(numBlocks)
        
        for (b in 0 until numBlocks) {
            val start = b * blockSize
            val amp = getAmplitude(data, start, 4096)
            if (amp > 1500f) {
                val rawPitch = detectPitch(data, start, 4096, sampleRate)
                blockPitches[b] = if (rawPitch in 100f..500f) quantizeToNote(rawPitch) else -1f
            } else {
                blockPitches[b] = -1f
            }
        }
        
        var lastValidPitch = 220f
        
        for (b in 0 until numBlocks) {
            val startSample = b * blockSize
            val endSample = min(length, startSample + blockSize)
            
            var rootFreq = blockPitches[b]
            if (rootFreq < 0f) rootFreq = lastValidPitch
            else lastValidPitch = rootFreq
            
            val freqs = floatArrayOf(rootFreq, rootFreq * 1.5f, rootFreq * 2.0f, rootFreq * 2.5f)
            val phases = FloatArray(freqs.size) { 0f }
            
            for (i in startSample until endSample) {
                val t = (i - startSample).toFloat() / sampleRate
                
                val attack = 0.015f
                val decay = 0.5f
                val envelope = if (t < attack) {
                    t / attack
                } else {
                    exp((-2.5 * (t - attack) / decay).toDouble()).toFloat()
                }
                
                var guitarSample = 0f
                for (j in freqs.indices) {
                    val phase = phases[j].toDouble()
                    val fundamental = sin(phase).toFloat()
                    val harmonic2 = (0.4 * sin(2.0 * phase)).toFloat()
                    val harmonic3 = (0.2 * sin(3.0 * phase)).toFloat()
                    val harmonic4 = (0.1 * sin(4.0 * phase)).toFloat()
                    
                    guitarSample += (fundamental + harmonic2 + harmonic3 + harmonic4) * (1.0f / freqs.size)
                    
                    phases[j] = (phases[j] + 2.0f * PI.toFloat() * freqs[j] / sampleRate)
                    if (phases[j] >= 2.0f * PI.toFloat()) {
                        phases[j] -= 2.0f * PI.toFloat()
                    }
                }
                
                // Supporting guitar - 25% volume
                output[i] = guitarSample * envelope * 0.25f
            }
        }
        
        val result = ShortArray(length)
        for (i in output.indices) {
            val clamped = max(-1f, min(1f, output[i]))
            result[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    // PERFECT BALANCE MIXER (80% vocal, 25% guitar)
    private fun mixPerfectBalance(vocal: ShortArray, guitar: ShortArray): ShortArray {
        val result = FloatArray(vocal.size)
        var maxPeak = 0f
        
        for (i in vocal.indices) {
            // 80% vocal, 25% guitar - voice is dominant
            val mixed = (vocal[i].toFloat() * 0.80f) + (guitar[i].toFloat() * 0.25f)
            result[i] = mixed
            val absVal = abs(mixed)
            if (absVal > maxPeak) maxPeak = absVal
        }
        
        // Normalize to 95% for loud, clean output
        val normalizeFactor = if (maxPeak > 0f) 0.95f / maxPeak else 1f
        
        val finalShort = ShortArray(result.size)
        for (i in result.indices) {
            val normalized = result[i] * normalizeFactor
            val clamped = max(-1f, min(1f, normalized))
            finalShort[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        
        return finalShort
    }

    private fun playSavedSong() {
        val uri = lastSavedUri
        if (uri == null) {
            Toast.makeText(this, "No song to play yet", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                start()
                setOnCompletionListener {
                    runOnUiThread {
                        btnPlay.text = "▶ Play My Song"
                        tvStatus.text = "Song finished. Tap Play to listen again."
                    }
                }
            }
            btnPlay.text = "⏸ Playing..."
            tvStatus.text = "Now playing your song..."
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun saveToSharedStorage(wavFile: File, fileName: String): Uri? {
        return try {
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
            newAudioUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
        mediaPlayer?.release()
    }
}
