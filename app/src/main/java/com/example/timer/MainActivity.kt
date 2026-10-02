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
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
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

    private var selectedImageUri: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
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
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Preparing ultra-fast render..."

        lifecycleScope.launch(Dispatchers.Default) {
            var pfd: ParcelFileDescriptor? = null
            try {
                val frameRate = 30
                val totalFrames = totalSeconds * frameRate
                val hasBg = cbBackground.isChecked && selectedImageUri != null
                
                var bgBitmap: Bitmap? = null
                if (hasBg) {
                    try {
                        contentResolver.openInputStream(selectedImageUri!!)?.use { input ->
                            val original = BitmapFactory.decodeStream(input)
                            if (original != null) {
                                bgBitmap = Bitmap.createScaledBitmap(original, 1920, 1080, true)
                                if (original != bgBitmap) original.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to load image, using black background", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Timer_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Timer")
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create MediaStore entry")

                pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw Exception("Failed to open file descriptor")

                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // UNIVERSAL CODEC - No explicit profile/level (lets device choose optimal)
                val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
                videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000) // 8 Mbps - perfect quality, faster encoding
                videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // Faster encoding
                
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = videoCodec.createInputSurface()
                videoCodec.start()

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

                val beepBytes = generatePerfectBeep(44100, 150, 880.0)

                // ULTRA-FAST TEXT RENDERING using shadow layer instead of stroke
                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 280f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    // Shadow layer is 10x faster than stroke
                    setShadowLayer(20f, 0f, 0f, Color.BLACK)
                }
                
                val metrics = textPaint.fontMetrics
                val textY = 540f - (metrics.descent + metrics.ascent) / 2f

                val bgRingPaint = Paint().apply {
                    color = Color.parseColor("#333333")
                    style = Paint.Style.STROKE
                    strokeWidth = 25f
                    isAntiAlias = true
                }
                val progressRingPaint = Paint().apply {
                    color = Color.parseColor("#00E5FF")
                    style = Paint.Style.STROKE
                    strokeWidth = 25f
                    isAntiAlias = true
                }
                val ringRect = RectF(360f, 140f, 1560f, 940f)

                withContext(Dispatchers.Main) { tvStatus.text = "Rendering at maximum speed..." }

                for (frame in 0 until totalFrames) {
                    val remainingSeconds = totalSeconds - (frame / frameRate)
                    if (remainingSeconds < 0) break

                    val timeString = formatTime(remainingSeconds)

                    val canvas = surface.lockCanvas(null)
                    val currentBgBitmap = bgBitmap
                    
                    if (hasBg && currentBgBitmap != null && !currentBgBitmap.isRecycled) {
                        canvas.drawBitmap(currentBgBitmap, null, Rect(0, 0, 1920, 1080), null)
                    } else {
                        canvas.drawColor(Color.BLACK)
                    }
                    
                    canvas.drawArc(ringRect, -90f, 360f, false, bgRingPaint)
                    val progressAngle = (remainingSeconds.toFloat() / totalSeconds) * 360f
                    canvas.drawArc(ringRect, -90f, progressAngle, false, progressRingPaint)

                    canvas.drawText(timeString, 960f, textY, textPaint)
                    
                    surface.unlockCanvasAndPost(canvas)

                    // Drain video output
                    var videoOutIndex = videoCodec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (videoOutIndex >= 0) {
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
                        videoOutIndex = videoCodec.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    // Audio processing
                    if (frame % frameRate == 0 && remainingSeconds > 0) {
                        val inputBufferIndex = audioCodec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = audioCodec.getInputBuffer(inputBufferIndex)!!
                            inputBuffer.clear()
                            val size = min(beepBytes.size, inputBuffer.remaining())
                            inputBuffer.put(beepBytes, 0, size)
                            val presentationTimeUs = (frame.toLong() * 1_000_000) / frameRate
                            audioCodec.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                        }
                    }
                    
                    var audioOutIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 10000)
                    while (audioOutIndex >= 0) {
                        if (audioBufferInfo.size > 0 && muxerStarted) {
                            val encodedData = audioCodec.getOutputBuffer(audioOutIndex)!!
                            encodedData.position(audioBufferInfo.offset)
                            encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        }
                        audioCodec.releaseOutputBuffer(audioOutIndex, false)
                        audioOutIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 0)
                    }

                    if (frame % 30 == 0) {
                        val progress = (frame * 100) / totalFrames
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress
                            tvStatus.text = "Generating: $progress%"
                        }
                    }
                }

                // Finalize
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
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoDone = true
                        videoCodec.releaseOutputBuffer(outIndex, false)
                    }
                }

                val audioInIndex = audioCodec.dequeueInputBuffer(10000)
                if (audioInIndex >= 0) {
                    audioCodec.queueInputBuffer(audioInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                var audioDone = false
                while (!audioDone) {
                    val outIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 10000)
                    if (outIndex >= 0) {
                        if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioDone = true
                        audioCodec.releaseOutputBuffer(outIndex, false)
                    }
                }

                videoCodec.stop(); videoCodec.release()
                audioCodec.stop(); audioCodec.release()
                muxer.stop(); muxer.release()
                bgBitmap?.recycle()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Saved to Movies/Timer folder!"
                    btnGenerate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Ultra-fast video saved!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Error: ${e.message}"
                    btnGenerate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pfd?.close()
            }
        }
    }

    private fun formatTime(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString(8) {
            if (h < 10) append('0')
            append(h).append(':')
            if (m < 10) append('0')
            append(m).append(':')
            if (s < 10) append('0')
            append(s)
        }
    }

    private fun generatePerfectBeep(sampleRate: Int, durationMs: Int, frequency: Double): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val generatedSnd = ByteArray(numSamples * 2)
        val fadeSamples = sampleRate / 100
        var idx = 0
        for (i in 0 until numSamples) {
            var envelope = 1.0
            if (i < fadeSamples) envelope = i.toDouble() / fadeSamples
            else if (i > numSamples - fadeSamples) envelope = (numSamples - i).toDouble() / fadeSamples
            
            val dVal = sin(2.0 * Math.PI * i * frequency / sampleRate) * envelope
            val sample = (dVal * 30000).toInt()
            generatedSnd[idx++] = (sample and 0x00FF).toByte()
            generatedSnd[idx++] = ((sample and 0xFF00) shr 8).toByte()
        }
        return generatedSnd
    }
}
