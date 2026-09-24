package com.example.autorecorder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.IOException
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: MaterialButton
    private lateinit var chkAutoZoom: CheckBox

    private val PERMISSION_REQUEST_CODE = 100
    private val SCREEN_CAPTURE_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        chkAutoZoom = findViewById(R.id.chkAutoZoom)

        createNotificationChannel()
        checkPermissions()

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                return@setOnClickListener
            }

            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recording_channel",
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        }
    }
}

class RecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isPaused = false

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var drawingOverlay: DrawingOverlayView? = null
    private var isDrawing = false

    private var tempFile: File? = null
    private var outputFile: String? = null

    // Fixed output video dimensions (landscape 16:9)
    private val outputWidth = 1920
    private val outputHeight = 1080

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        startForegroundNotification()
        createFloatingControlPanel()
        startRecording()

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "recording_channel")
            .setContentTitle("Auto Recorder")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createFloatingControlPanel() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(12, 12, 12, 12)
        }

        val btnRecord = createFloatingButton("●", Color.RED) { toggleRecording() }
        val btnPause = createFloatingButton("⏸", Color.YELLOW) { pauseRecording() }
        val btnStop = createFloatingButton("⏹", Color.WHITE) { stopRecordingAndProcess() }
        val btnDraw = createFloatingButton("✎", Color.CYAN) { toggleDrawing() }

        container.addView(btnRecord)
        container.addView(btnPause)
        container.addView(btnStop)
        container.addView(btnDraw)

        floatingView = container
        windowManager?.addView(floatingView, layoutParams)
    }

    private fun createFloatingButton(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val size = 100
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(color)
            setPadding(16, 16, 16, 16)
            setOnClickListener { onClick() }
        }
    }

    private fun startRecording() {
        val timestamp = System.currentTimeMillis()
        val tempFileName = "AutoRecorder_temp_$timestamp.mp4"
        tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), tempFileName)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoSize(screenWidth, screenHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(8000000)
            setVideoFrameRate(30)
            setOutputFile(tempFile?.absolutePath)
            prepare()
        }

        val surface = mediaRecorder?.surface ?: return

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            screenWidth,
            screenHeight,
            320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        mediaRecorder?.start()
        isRecording = true
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording() {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecordingAndProcess()
        }
    }

    private fun pauseRecording() {
        if (isRecording && !isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused = true
                Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show()
            }
        } else if (isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                isPaused = false
                Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null

            Toast.makeText(this, "Processing video with gradient background...", Toast.LENGTH_LONG).show()

            Thread {
                processVideoWithGradient()
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error stopping recording", Toast.LENGTH_SHORT).show()
            stopForegroundService()
        }
    }

    private fun processVideoWithGradient() {
        if (tempFile == null || !tempFile!!.exists()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "No video to process", Toast.LENGTH_SHORT).show()
                stopForegroundService()
            }
            return
        }

        val timestamp = System.currentTimeMillis()
        outputFile = "AutoRecorder_1920x1080_$timestamp.mp4"
        val finalFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), outputFile)

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile!!.absolutePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && videoTrackIndex == -1) {
                    videoTrackIndex = i
                } else if (mime?.startsWith("audio/") == true && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) {
                throw Exception("No video track found")
            }

            extractor.selectTrack(videoTrackIndex)
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                30
            }

            val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight)
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8000000)
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(finalFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrackIndex = -1
            var muxerAudioTrackIndex = -1
            var muxerStarted = false

            val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            val paint = Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }

            var gradientOffset = 0f
            val gradientIncrement = 0.01f

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var decoderOutputAvailable = true
                var encoderOutputAvailable = true

                while (decoderOutputAvailable || encoderOutputAvailable) {
                    val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    decoderOutputAvailable = decoderOutputBufferIndex >= 0

                    if (decoderOutputBufferIndex >= 0) {
                        val doRender = bufferInfo.size != 0
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex, doRender)

                        if (doRender) {
                            val image = decoder.getOutputImage(decoderOutputBufferIndex)
                            
                            if (image != null) {
                                val inputBitmap = imageToBitmap(image)
                                
                                // Draw animated gradient background
                                drawGradientBackground(canvas, gradientOffset)
                                gradientOffset += gradientIncrement
                                if (gradientOffset > 1f) gradientOffset = 0f

                                // Calculate perfect centering using minOf
                                val scaleX = outputWidth.toFloat() / width.toFloat()
                                val scaleY = outputHeight.toFloat() / height.toFloat()
                                val scale = minOf(scaleX, scaleY)

                                val scaledWidth = (width * scale).toInt()
                                val scaledHeight = (height * scale).toInt()

                                // Perfect center calculation
                                val left = (outputWidth - scaledWidth) / 2
                                val top = (outputHeight - scaledHeight) / 2
                                val right = left + scaledWidth
                                val bottom = top + scaledHeight

                                val srcRect = Rect(0, 0, width, height)
                                val dstRect = Rect(left, top, right, bottom)

                                canvas.drawBitmap(inputBitmap, srcRect, dstRect, paint)
                                inputBitmap.recycle()
                                image.close()

                                val encoderCanvas = Canvas(encoderSurface)
                                encoderCanvas.drawBitmap(outputBitmap, 0f, 0f, null)
                            }
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            outputDone = true
                        }
                    }

                    val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    encoderOutputAvailable = encoderOutputBufferIndex >= 0

                    if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        encoderOutputAvailable = false
                    } else if (encoderOutputBufferIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex)
                        if (encodedData != null && bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                val outputFormat2 = encoder.outputFormat
                                muxerVideoTrackIndex = muxer.addTrack(outputFormat2)
                                
                                if (audioTrackIndex != -1) {
                                    extractor.unselectTrack(videoTrackIndex)
                                    extractor.selectTrack(audioTrackIndex)
                                    val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                                    muxerAudioTrackIndex = muxer.addTrack(audioFormat)
                                    extractor.unselectTrack(audioTrackIndex)
                                    extractor.selectTrack(videoTrackIndex)
                                }
                                
                                muxer.start()
                                muxerStarted = true
                            }

                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            extractor.release()

            outputBitmap.recycle()

            saveToMediaStore(finalFile)
            tempFile?.delete()

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Video saved with gradient background (1920x1080)", Toast.LENGTH_LONG).show()
                stopForegroundService()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Error processing video: ${e.message}", Toast.LENGTH_LONG).show()
                stopForegroundService()
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun drawGradientBackground(canvas: Canvas, offset: Float) {
        val paint = Paint()
        val colors = intArrayOf(
            Color.parseColor("#FF6B6B"),
            Color.parseColor("#4ECDC4"),
            Color.parseColor("#45B7D1"),
            Color.parseColor("#96CEB4"),
            Color.parseColor("#FFEAA7"),
            Color.parseColor("#DDA0DD"),
            Color.parseColor("#FF6B6B")
        )

        val shader = LinearGradient(
            0f,
            0f,
            outputWidth.toFloat(),
            outputHeight.toFloat(),
            colors,
            null,
            Shader.TileMode.MIRROR
        )

        val matrix = Matrix()
        matrix.setRotate(offset * 360, outputWidth / 2f, outputHeight / 2f)
        shader.setLocalMatrix(matrix)

        paint.shader = shader
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), paint)
    }

    private fun saveToMediaStore(finalFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputFile)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AutoRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { outputStream ->
                    finalFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            } catch (e: IOException) {
                e.printStackTrace()
                resolver.delete(it, null, null)
            }
        }
    }

    private fun toggleDrawing() {
        isDrawing = !isDrawing
        if (isDrawing) {
            createDrawingOverlay()
        } else {
            removeDrawingOverlay()
        }
    }

    private fun createDrawingOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        drawingOverlay = DrawingOverlayView(this)
        windowManager?.addView(drawingOverlay, layoutParams)
    }

    private fun removeDrawingOverlay() {
        drawingOverlay?.let {
            windowManager?.removeView(it)
            drawingOverlay = null
        }
    }

    private fun stopForegroundService() {
        removeDrawingOverlay()
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundService()
    }

    inner class DrawingOverlayView(context: Context) : View(context) {
        private val paths = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        private var currentPaint: Paint? = null

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for ((path, paint) in paths) {
                canvas.drawPath(path, paint)
            }
            currentPath?.let { path ->
                currentPaint?.let { paint ->
                    canvas.drawPath(path, paint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = Path()
                    currentPaint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 8f
                        isAntiAlias = true
                        strokeJoin = Paint.Join.ROUND
                        strokeCap = Paint.Cap.ROUND
                    }
                    currentPath?.moveTo(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    currentPath?.let { path ->
                        currentPaint?.let { paint ->
                            paths.add(Pair(path, paint))
                        }
                    }
                    currentPath = null
                    currentPaint = null
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
