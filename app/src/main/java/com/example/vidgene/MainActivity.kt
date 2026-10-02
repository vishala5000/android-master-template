package com.example.timer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etSeconds: EditText
    private lateinit var cbBackground: CheckBox
    private lateinit var btnPickImage: Button
    private lateinit var btnGenerate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnDownload: Button

    private var selectedImageUri: Uri? = null
    private var generatedVideoUri: Uri? = null
    private var tempVideoFile: File? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required for image selection and saving", Toast.LENGTH_LONG).show()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHours = findViewById(R.id.etHours)
        etMinutes = findViewById(R.id.etMinutes)
        etSeconds = findViewById(R.id.etSeconds)
        cbBackground = findViewById(R.id.cbBackground)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnGenerate = findViewById(R.id.btnGenerate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        btnDownload = findViewById(R.id.btnDownload)

        checkPermissions()

        cbBackground.setOnCheckedChangeListener { _, isChecked ->
            btnPickImage.isEnabled = isChecked
            if (!isChecked) selectedImageUri = null
        }

        btnPickImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        btnGenerate.setOnClickListener {
            val h = etHours.text.toString().toIntOrNull() ?: 0
            val m = etMinutes.text.toString().toIntOrNull() ?: 0
            val s = etSeconds.text.toString().toIntOrNull() ?: 0
            val totalSeconds = (h * 3600) + (m * 60) + s

            if (totalSeconds <= 0) {
                Toast.makeText(this, "Please enter a valid time greater than 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (cbBackground.isChecked && selectedImageUri == null) {
                Toast.makeText(this, "Please pick a background image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startVideoGeneration(totalSeconds)
        }

        btnDownload.setOnClickListener {
            saveToPublicFolder()
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun startVideoGeneration(totalSeconds: Int) {
        btnGenerate.isEnabled = false
        btnDownload.visibility = Button.GONE
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Preparing generation..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                tempVideoFile = File(cacheDir, "temp_countdown.mp4")
                if (tempVideoFile!!.exists()) tempVideoFile!!.delete()

                val frameRate = 30
                val totalFrames = totalSeconds * frameRate
                val hasBg = cbBackground.isChecked && selectedImageUri != null
                
                var bgBitmap: Bitmap? = null
                if (hasBg) {
                    contentResolver.openInputStream(selectedImageUri!!)?.use { input ->
                        val original = BitmapFactory.decodeStream(input)
                        bgBitmap = Bitmap.createScaledBitmap(original, 1920, 1080, true)
                    }
                }

                val muxer = MediaMuxer(tempVideoFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // Video Codec Setup
                val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
                videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
                videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = videoCodec.createInputSurface()
                videoCodec.start()

                // Audio Codec Setup (AAC)
                val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1)
                audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()

                var videoTrackIndex = -1
                var audioTrackIndex = -1
                var muxerStarted = false

                val bufferInfo = MediaCodec.BufferInfo()
                val audioBufferInfo = MediaCodec.BufferInfo()

                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 200f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                }
                val strokePaint = Paint().apply {
                    color = Color.BLACK
                    textSize = 200f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                    style = Paint.Style.STROKE
                    strokeWidth = 25f
                }

                withContext(Dispatchers.Main) { tvStatus.text = "Rendering frames..." }

                for (frame in 0 until totalFrames) {
                    val remainingSeconds = totalSeconds - (frame / frameRate)
                    if (remainingSeconds < 0) break

                    val timeString = String.format("%02d:%02d:%02d", 
                        remainingSeconds / 3600, 
                        (remainingSeconds % 3600) / 60, 
                        remainingSeconds % 60
                    )

                    // 1. Draw Video Frame
                    val canvas = surface.lockCanvas(null)
                    if (hasBg && bgBitmap != null) {
                        canvas.drawBitmap(bgBitmap, null, Rect(0, 0, 1920, 1080), null)
                        canvas.drawText(timeString, 960f, 590f, strokePaint)
                    } else {
                        canvas.drawColor(Color.BLACK)
                    }
                    canvas.drawText(timeString, 960f, 590f, textPaint)
                    surface.unlockCanvasAndPost(canvas)

                    // 2. Process Video Output
                    val videoOutIndex = videoCodec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (videoOutIndex >= 0) {
                        if (!muxerStarted && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Skip config frame for muxer start, wait for actual data
                        }
                        if (bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                                audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            val encodedData = videoCodec.getOutputBuffer(videoOutIndex)!!
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        videoCodec.releaseOutputBuffer(videoOutIndex, false)
                    }

                    // 3. Process Audio Output (Beep every 1 second / 30 frames)
                    if (frame % frameRate == 0 && remainingSeconds > 0 && remainingSeconds <= 10) {
                        val beepBytes = generateBeep(44100, 400, 880.0)
                        val inputBufferIndex = audioCodec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = audioCodec.getInputBuffer(inputBufferIndex)!!
                            inputBuffer.clear()
                            val size = minOf(beepBytes.size, inputBuffer.remaining())
                            inputBuffer.put(beepBytes, 0, size)
                            audioCodec.queueInputBuffer(inputBufferIndex, 0, size, (frame.toLong() * 1_000_000) / frameRate, 0)
                        }
                    }
                    
                    val audioOutIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 10000)
                    if (audioOutIndex >= 0) {
                        if (audioBufferInfo.size > 0 && muxerStarted) {
                            val encodedData = audioCodec.getOutputBuffer(audioOutIndex)!!
                            encodedData.position(audioBufferInfo.offset)
                            encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        }
                        audioCodec.releaseOutputBuffer(audioOutIndex, false)
                    }

                    // Update Progress
                    if (frame % 30 == 0) {
                        val progress = (frame * 100) / totalFrames
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress
                            tvStatus.text = "Generating: $progress%"
                        }
                    }
                }

                // End of stream
                videoCodec.signalEndOfInputStream()
                var videoDone = false
                while (!videoDone) {
                    val outIndex = videoCodec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outIndex >= 0) {
                        if (bufferInfo.size > 0 && muxerStarted) {
                            val encodedData = videoCodec.getOutputBuffer(outIndex)!!
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            videoDone = true
                        }
                        videoCodec.releaseOutputBuffer(outIndex, false)
                    }
                }

                audioCodec.signalEndOfInputStream()
                var audioDone = false
                while (!audioDone) {
                    val outIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 10000)
                    if (outIndex >= 0) {
                        if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            audioDone = true
                        }
                        audioCodec.releaseOutputBuffer(outIndex, false)
                    }
                }

                videoCodec.stop()
                videoCodec.release()
                audioCodec.stop()
                audioCodec.release()
                muxer.stop()
                muxer.release()
                bgBitmap?.recycle()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Generation Complete!"
                    btnDownload.visibility = Button.VISIBLE
                    btnGenerate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Video generated successfully", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Error: ${e.message}"
                    btnGenerate.isEnabled = true
                }
            }
        }
    }

    private fun generateBeep(sampleRate: Int, durationMs: Int, frequency: Double): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val generatedSnd = ByteArray(numSamples * 2)
        var idx = 0
        for (i in 0 until numSamples) {
            val dVal = sin(2 * Math.PI * i * frequency / sampleRate).toFloat()
            val sample = (dVal * 32767).toInt().toShort()
            generatedSnd[idx++] = (sample and 0x00ff).toByte()
            generatedSnd[idx++] = ((sample and 0xff00) shr 8).toByte()
        }
        return generatedSnd
    }

    private fun saveToPublicFolder() {
        if (tempVideoFile == null || !tempVideoFile!!.exists()) return

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "countdown_timer_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Timer")
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        tempVideoFile!!.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Saved to Movies/Timer folder", Toast.LENGTH_LONG).show()
                        btnDownload.visibility = Button.GONE
                        tvStatus.text = "Saved to internal storage"
                    }
                } else {
                    throw Exception("Failed to create MediaStore entry")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
