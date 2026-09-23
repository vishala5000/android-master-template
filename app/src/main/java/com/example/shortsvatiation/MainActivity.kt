package com.example.shortsvatiation

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaCodec
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
               
