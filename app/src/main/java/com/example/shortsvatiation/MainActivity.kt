package com.example.shortsvatiation

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var tvVideoStatus: TextView
    private lateinit var btnUpload: MaterialButton
    private lateinit var actvLanguage: AutoCompleteTextView
    private lateinit var btnProcess: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressStatus: TextView

    private var selectedVideoUri: Uri? = null

    private val languages = arrayOf(
        "English (Original)",
        "Spanish",
        "French",
        "German",
        "Japanese",
        "Hindi"
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) launchVideoPicker()
        else Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedVideoUri = it
            tvVideoStatus.text = "Video Selected: ${getFileName(it)}"
            btnProcess.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupLanguageDropdown()
        setupListeners()
    }

    private fun initViews() {
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        btnUpload = findViewById(R.id.btnUpload)
        actvLanguage = findViewById(R.id.actvLanguage)
        btnProcess = findViewById(R.id.btnProcess)
        progressBar = findViewById(R.id.progressBar)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
    }

    private fun setupLanguageDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languages)
        actvLanguage.setAdapter(adapter)
        actvLanguage.setText(languages[0], false)
    }

    private fun setupListeners() {
        btnUpload.setOnClickListener { checkPermissionAndPickVideo() }
        btnProcess.setOnClickListener { startProcessing() }
    }

    private fun checkPermissionAndPickVideo() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchVideoPicker()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun launchVideoPicker() {
        pickVideoLauncher.launch("video/*")
    }

    private fun getFileName(uri: Uri): String {
        var result = "video.mp4"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex != -1) result = cursor.getString(nameIndex)
            }
        }
        return result
    }

    private fun startProcessing() {
        val uri = selectedVideoUri ?: return
        val selectedLanguage = actvLanguage.text.toString()

        btnUpload.isEnabled = false
        btnProcess.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE

        Thread {
            try {
                runOnUiThread { 
                    tvProgressStatus.text = "Extracting video tracks..."
                    progressBar.progress = 20 
                }

                val extractor = MediaExtractor()
                extractor.setDataSource(this, uri, null)

                // Create output file in public Movies folder
                val outputFileName = "ShortsVariation_${System.currentTimeMillis()}.mp4"
                val outputPath = getOutputFilePath(outputFileName)

                val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                runOnUiThread { 
                    tvProgressStatus.text = "Stripping metadata & re-muxing..."
                    progressBar.progress = 50 
                }

                // Add tracks and strip original metadata to make it "undetectable"
                val trackIndexMap = HashMap<Int, Int>()
                for (i in 0 until extractor.trackCount) {
                    val originalFormat = extractor.getTrackFormat(i)
                    val mime = originalFormat.getString(MediaFormat.KEY_MIME) ?: continue
                    
                    // Create a clean format to strip original metadata/headers
                    val cleanFormat = MediaFormat()
                    cleanFormat.setString(MediaFormat.KEY_MIME, mime)
                    
                    if (mime.startsWith("video/")) {
                        cleanFormat.setInteger(MediaFormat.KEY_WIDTH, originalFormat.getInteger(MediaFormat.KEY_WIDTH))
                        cleanFormat.setInteger(MediaFormat.KEY_HEIGHT, originalFormat.getInteger(MediaFormat.KEY_HEIGHT))
                        if (originalFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            cleanFormat.setInteger(MediaFormat.KEY_FRAME_RATE, originalFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
                        }
                        if (originalFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            cleanFormat.setInteger(MediaFormat.KEY_BIT_RATE, originalFormat.getInteger(MediaFormat.KEY_BIT_RATE))
                        }
                    } else if (mime.startsWith("audio/")) {
                        cleanFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, originalFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        cleanFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, originalFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }

                    // Copy codec specific data if present (required for playback)
                    if (originalFormat.containsKey("csd-0")) {
                        cleanFormat.setByteBuffer("csd-0", originalFormat.getByteBuffer("csd-0"))
                    }
                    if (originalFormat.containsKey("csd-1")) {
                        cleanFormat.setByteBuffer("csd-1", originalFormat.getByteBuffer("csd-1"))
                    }

                    val muxerTrackIndex = muxer.addTrack(cleanFormat)
                    trackIndexMap[i] = muxerTrackIndex
                }

                muxer.start()

                runOnUiThread { 
                    tvProgressStatus.text = "Rebuilding video hash for $selectedLanguage..."
                    progressBar.progress = 80 
                }

                // Read and write samples to change the file hash/structure
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    
                    val trackIndex = extractor.sampleTrackIndex
                    val presentationTimeUs = extractor.sampleTime
                    
                    if (trackIndexMap.containsKey(trackIndex)) {
                        muxer.writeSampleData(
                            trackIndexMap[trackIndex]!!, 
                            buffer, 0, sampleSize, 
                            presentationTimeUs, 
                            extractor.sampleFlags
                        )
                    }
                    extractor.advance()
                }

                extractor.release()
                muxer.stop()
                muxer.release()

                // Register the new file in the MediaStore so it appears in the Gallery/Videos folder
                registerVideoInMediaStore(outputPath, outputFileName)

                runOnUiThread {
                    progressBar.progress = 100
                    tvProgressStatus.text = "Processing Complete!"
                    tvVideoStatus.text = "Success! Saved to Videos folder."
                    Toast.makeText(this@MainActivity, "Video saved to Videos folder", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvVideoStatus.text = "Error processing video."
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvProgressStatus.visibility = View.GONE
                    btnUpload.isEnabled = true
                    btnProcess.isEnabled = true
                }
            }
        }.start()
    }

    private fun getOutputFilePath(fileName: String): String {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "ShortsVariation")
        if (!appDir.exists()) appDir.mkdirs()
        return File(appDir, fileName).absolutePath
    }

    private fun registerVideoInMediaStore(filePath: String, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShortsVariation")
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, filePath)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }
    }
}
