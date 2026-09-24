package com.example.singsong

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnPlay: MaterialButton

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private var mixedFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        fabRecord = findViewById(R.id.fabRecord)
        btnPlay = findViewById(R.id.btnPlay)

        checkPermissions()

        fabRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        btnPlay.setOnClickListener { playMixedTrack() }
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun startRecording() {
        isRecording = true
        tvStatus.text = "Recording... Sing now!"
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize
        )
        audioRecord?.startRecording()
        
        recordingThread = Thread {
            val audioData = ShortArray(bufferSize)
            val rawData = mutableListOf<Short>()
            
            while (isRecording) {
                val read = audioRecord?.read(audioData, 0, bufferSize) ?: -1
                if (read > 0) {
                    for (i in 0 until read) rawData.add(audioData[i])
                }
            }
            processAndMixAudio(rawData.toShortArray())
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        tvStatus.text = "Processing your song... Adding magic!"
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        
        recordingThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processAndMixAudio(rawData: ShortArray) {
        if (rawData.isEmpty()) {
            runOnUiThread { tvStatus.text = "No audio recorded." }
            return
        }

        // 1. Apply Echo Effect
        val echoedData = applyEcho(rawData)
        
        // 2. Detect Pitch to generate matching music
        val detectedPitch = detectPitch(echoedData)
        
        // 3. Generate Backing Track matching the pitch
        val backingTrack = generateBackingTrack(detectedPitch, echoedData.size)
        
        // 4. Mix Voice and Backing Track
        val mixedData = mixAudio(echoedData, backingTrack)
        
        // 5. Save to WAV
        saveToWav(mixedData)
        
        runOnUiThread {
            tvStatus.text = "Done! Your song is ready."
            btnPlay.isEnabled = true
        }
    }

    private fun applyEcho(data: ShortArray): ShortArray {
        val delaySamples = sampleRate / 4 // 0.25s delay
        val decay = 0.5f
        val result = ShortArray(data.size)
        
        for (i in data.indices) {
            var sample = data[i].toFloat()
            if (i >= delaySamples) sample += result[i - delaySamples].toFloat() * decay
            result[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return result
    }

    private fun detectPitch(data: ShortArray): Float {
        val chunkSize = 4096
        if (data.size < chunkSize) return 220.0f 
        
        val chunk = FloatArray(chunkSize)
        for (i in 0 until chunkSize) chunk[i] = data[i].toFloat()
        
        val maxVal = chunk.maxOrNull() ?: 1f
        if (maxVal == 0f) return 220.0f
        for (i in chunk.indices) chunk[i] /= maxVal
        
        var bestLag = 0
        var maxCorrelation = 0f
        val minLag = sampleRate / 1000 
        val maxLag = sampleRate / 70   
        
        for (lag in minLag..maxLag) {
            var correlation = 0f
            for (i in 0 until chunkSize - lag) correlation += chunk[i] * chunk[i + lag]
            if (correlation > maxCorrelation) {
                maxCorrelation = correlation
                bestLag = lag
            }
        }
        return if (bestLag > 0) sampleRate.toFloat() / bestLag else 220.0f
    }

    private fun generateBackingTrack(pitch: Float, length: Int): ShortArray {
        val track = ShortArray(length)
        val notes = floatArrayOf(130.81f, 146.83f, 155.56f, 174.61f, 196.00f, 207.65f, 233.08f, 261.63f)
        
        var closestNote = notes[0]
        var minDiff = Float.MAX_VALUE
        for (note in notes) {
            val diff = abs(pitch - note)
            if (diff < minDiff) { minDiff = diff; closestNote = note }
        }
        
        val bassFreq = closestNote / 2 
        
        for (i in track.indices) {
            val t = i.toFloat() / sampleRate
            val bass = sin(2.0 * PI * bassFreq * t) * 0.4
            
            var kick = 0.0
            val timeInBeat = t % 0.5f
            if (timeInBeat < 0.1f) {
                val kickFreq = 150.0 - (timeInBeat / 0.1f) * 100.0
                kick = sin(2.0 * PI * kickFreq * timeInBeat) * (1.0 - timeInBeat / 0.1f) * 0.6
            }
            
            var hihat = 0.0
            val timeInHihat = t % 0.25f
            if (timeInHihat < 0.05f) {
                hihat = (Random.nextDouble() * 2.0 - 1.0) * (1.0 - timeInHihat / 0.05f) * 0.2
            }
            
            val sample = (bass + kick + hihat) * Short.MAX_VALUE
            track[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return track
    }

    private fun mixAudio(voice: ShortArray, music: ShortArray): ShortArray {
        val length = minOf(voice.size, music.size)
        val mixed = ShortArray(length)
        for (i in 0 until length) {
            val sum = voice[i].toFloat() * 0.8f + music[i].toFloat() * 0.6f
            mixed[i] = sum.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mixed
    }

    private fun saveToWav(data: ShortArray) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (dir != null) {
            mixedFile = File(dir, "SingSong_Mixed_${System.currentTimeMillis()}.wav")
            try {
                val fos = FileOutputStream(mixedFile)
                val totalAudioLen = data.size * 2
                val totalDataLen = totalAudioLen + 36
                val byteRate = 16 * sampleRate * 1 / 8
                
                val header = ByteArray(44)
                header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
                header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen >> 8) and 0xff).toByte()
                header[6] = ((totalDataLen >> 16) and 0xff).toByte(); header[7] = ((totalDataLen >> 24) and 0xff).toByte()
                header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
                header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 
                header[20] = 1; header[21] = 0 
                header[22] = 1; header[23] = 0 
                header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate >> 8) and 0xff).toByte()
                header[26] = ((sampleRate >> 16) and 0xff).toByte(); header[27] = ((sampleRate >> 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate >> 8) and 0xff).toByte()
                header[30] = ((byteRate >> 16) and 0xff).toByte(); header[31] = ((byteRate >> 24) and 0xff).toByte()
                header[32] = 2; header[33] = 0 
                header[34] = 16; header[35] = 0 
                header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen >> 8) and 0xff).toByte()
                header[42] = ((totalAudioLen >> 16) and 0xff).toByte(); header[43] = ((totalAudioLen >> 24) and 0xff).toByte()
                
                fos.write(header)
                val buffer = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in data) buffer.putShort(sample)
                fos.write(buffer.array())
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun playMixedTrack() {
        if (mixedFile != null && mixedFile!!.exists()) {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                try {
                    setDataSource(this@MainActivity, Uri.fromFile(mixedFile))
                    prepare()
                    start()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Error playing track", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
