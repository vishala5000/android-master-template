package com.example.singsong

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
import org.tensorflow.lite.Interpreter

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private var autoTuneInterpreter: Interpreter? = null
    private var musicGenInterpreter: Interpreter? = null
    private var hasAutoTuneModel = false
    private var hasMusicGenModel = false

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
        loadAIModels()

        btnRecord.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecordingAndProcess() }
    }
    
    private fun loadAIModels() {
        try {
            val autoTuneModel = loadModelFile("autotune_model.tflite")
            if (autoTuneModel != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    useNNAPI()
                }
                autoTuneInterpreter = Interpreter(autoTuneModel, options)
                hasAutoTuneModel = true
            }
            
            val musicGenModel = loadModelFile("musicgen_model.tflite")
            if (musicGenModel != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    useNNAPI()
                }
                musicGenInterpreter = Interpreter(musicGenModel, options)
                hasMusicGenModel = true
            }
            
            if (hasAutoTuneModel || hasMusicGenModel) {
                tvStatus.text = "AI Models loaded! Professional mode ready."
            } else {
                tvStatus.text = "Using DSP engine. Ready to record!"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Using DSP engine. Ready to record!"
        }
    }
    
    private fun loadModelFile(filename: String): MappedByteBuffer? {
        return try {
            val fileDescriptor: AssetFileDescriptor = assets.openFd(filename)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            null
        }
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
        tvStatus.text = if (hasAutoTuneModel || hasMusicGenModel) {
            "Processing with AI (fast)..."
        } else {
            "Processing with DSP..."
        }
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
                
                val processedVocal = if (hasAutoTuneModel && autoTuneInterpreter != null) {
                    applyAIAutoTune(pcmData)
                } else {
                    applyProfessionalVocalProcessing(pcmData)
                }
                
                runOnUiThread { progressBar.progress = 50 }
                
                val backingTrack = if (hasMusicGenModel && musicGenInterpreter != null) {
                    generateAIMusicTrack(pcmData)
                } else {
                    generateProfessionalGuitarTrack(pcmData, 44100)
                }
                
                runOnUiThread { progressBar.progress = 75 }
                val finalMix = mixTracksProfessional(processedVocal, backingTrack)
                
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
                    val msg = if (hasAutoTuneModel || hasMusicGenModel) {
                        "AI-powered mix saved!"
                    } else {
                        "DSP mix saved!"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
    
    private fun applyAIAutoTune(input: ShortArray): ShortArray {
        return try {
            val inputBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = inputBuffer.asFloatBuffer()
            for (sample in input) {
                floatBuffer.put(sample.toFloat() / Short.MAX_VALUE)
            }
            inputBuffer.rewind()
            
            val outputBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
            autoTuneInterpreter?.run(inputBuffer, outputBuffer)
            
            outputBuffer.rewind()
            val outputFloat = outputBuffer.asFloatBuffer()
            val result = ShortArray(input.size)
            for (i in result.indices) {
                val sample = outputFloat.get(i)
                result[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            result
        } catch (e: Exception) {
            applyProfessionalVocalProcessing(input)
        }
    }
    
    private fun generateAIMusicTrack(input: ShortArray): ShortArray {
        return try {
            val inputBuffer = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = inputBuffer.asFloatBuffer()
            
            val blockSize = min(4096, input.size)
            for (i in 0 until blockSize) {
                floatBuffer.put(input[i].toFloat() / Short.MAX_VALUE)
            }
            while (floatBuffer.hasRemaining()) {
                floatBuffer.put(0f)
            }
            inputBuffer.rewind()
            
            val outputBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
            musicGenInterpreter?.run(inputBuffer, outputBuffer)
            
            outputBuffer.rewind()
            val outputFloat = outputBuffer.asFloatBuffer()
            val result = ShortArray(input.size)
            for (i in result.indices) {
                val sample = outputFloat.get(i)
                result[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            result
        } catch (e: Exception) {
            generateProfessionalGuitarTrack(input, 44100)
        }
    }

    private fun applyProfessionalVocalProcessing(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val sampleRate = 44100f
        val output = FloatArray(input.size)
        
        var maxVal = 0f
        for (i in input.indices) {
            val absVal = abs(input[i].toFloat())
            if (absVal > maxVal) maxVal = absVal
        }
        val normFactor = if (maxVal > 0f) (Short.MAX_VALUE.toFloat() / maxVal) * 0.92f else 1f
        
        var lpState = 0f
        val alpha = 0.25f
        
        val delay1 = (0.029f * sampleRate).toInt()
        val delay2 = (0.037f * sampleRate).toInt()
        val delay3 = (0.043f * sampleRate).toInt()
        val delay4 = (0.053f * sampleRate).toInt()
        
        val buf1 = FloatArray(delay1)
        val buf2 = FloatArray(delay2)
        val buf3 = FloatArray(delay3)
        val buf4 = FloatArray(delay4)
        var p1 = 0; var p2 = 0; var p3 = 0; var p4 = 0
        
        for (i in input.indices) {
            var s = (input[i].toFloat() * normFactor) / Short.MAX_VALUE
            lpState += alpha * (s - lpState)
            s = lpState
            
            val d1 = buf1[p1] * 0.6f
            val d2 = buf2[p2] * 0.5f
            val d3 = buf3[p3] * 0.4f
            val d4 = buf4[p4] * 0.3f
            
            buf1[p1] = s + d1 * 0.5f
            buf2[p2] = s + d2 * 0.5f
            buf3[p3] = s + d3 * 0.5f
            buf4[p4] = s + d4 * 0.5f
            
            output[i] = s * 0.65f + (d1 + d2 + d3 + d4) * 0.35f
            
            p1 = (p1 + 1) % delay1
            p2 = (p2 + 1) % delay2
            p3 = (p3 + 1) % delay3
            p4 = (p4 + 1) % delay4
        }
        
        val threshold = 0.55f
        val ratio = 3.5f
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

    private fun detectPitchAdvanced(data: ShortArray, start: Int, len: Int, sampleRate: Int): Float {
        val block = FloatArray(len)
        for (i in 0 until len) block[i] = data[start + i].toFloat()
        
        var bestCorrelation = -1f
        var bestPeriod = -1
        val minPeriod = sampleRate / 1000
        val maxPeriod = sampleRate / 60
        
        for (period in minPeriod until maxPeriod) {
            var correlation = 0f
            for (i in 0 until len - period) {
                correlation += block[i] * block[i + period]
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestPeriod = period
            }
        }
        
        return if (bestPeriod > 0) sampleRate.toFloat() / bestPeriod else -1f
    }

    private fun quantizeToScale(freq: Float): Float {
        if (freq <= 0f) return -1f
        val midi = 69f + 12f * (ln(freq / 440.0f) / ln(2.0f))
        val roundedMidi = midi.roundToInt()
        return 440f * 2.0.pow((roundedMidi - 69) / 12.0).toFloat()
    }

    private fun generateProfessionalGuitarTrack(data: ShortArray, sampleRate: Int): ShortArray {
        val length = data.size
        val output = FloatArray(length)
        
        val blockSize = 22050
        val numBlocks = length / blockSize
        val blockPitches = FloatArray(numBlocks)
        
        for (b in 0 until numBlocks) {
            val start = b * blockSize
            val amp = getAmplitude(data, start, 4096)
            if (amp > 1500f) {
                val rawPitch = detectPitchAdvanced(data, start, 4096, sampleRate)
                blockPitches[b] = if (rawPitch in 100f..500f) quantizeToScale(rawPitch) else -1f
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
                
                val attack = 0.008f
                val decay = 0.6f
                var envelope = if (t < attack) {
                    t / attack
                } else {
                    exp(-2.5f * (t - attack) / decay).toFloat()
                }
                
                var guitarSample = 0f
                for (j in freqs.indices) {
                    val fundamental = sin(phases[j])
                    val harmonic2 = 0.4f * sin(2.0 * phases[j])
                    val harmonic3 = 0.2f * sin(3.0 * phases[j])
                    val harmonic4 = 0.1f * sin(4.0 * phases[j])
                    
                    guitarSample += (fundamental + harmonic2 + harmonic3 + harmonic4) * (1.0f / freqs.size)
                    
                    phases[j] = (phases[j] + 2.0f * PI.toFloat() * freqs[j] / sampleRate)
                    if (phases[j] >= 2.0f * PI.toFloat()) {
                        phases[j] -= 2.0f * PI.toFloat()
                    }
                }
                
                output[i] += guitarSample * envelope * 0.55f
            }
        }
        
        val result = ShortArray(length)
        for (i in output.indices) {
            val clamped = max(-1f, min(1f, output[i]))
            result[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    private fun getAmplitude(data: ShortArray, start: Int, len: Int): Float {
        var sum = 0f
        val end = min(data.size, start + len)
        for (i in start until end) sum += abs(data[i].toFloat())
        return sum / (end - start)
    }

    private fun mixTracksProfessional(vocal: ShortArray, backing: ShortArray): ShortArray {
        val result = ShortArray(vocal.size)
        for (i in vocal.indices) {
            val mixed = (vocal[i].toFloat() * 0.60f) + (backing[i].toFloat() * 0.55f)
            val clamped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), mixed))
            result[i] = clamped.toInt().toShort()
        }
        return result
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
        autoTuneInterpreter?.close()
        musicGenInterpreter?.close()
    }
}
