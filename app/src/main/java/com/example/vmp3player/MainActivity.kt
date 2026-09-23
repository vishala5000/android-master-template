package com.example.vmp3player

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var btnPlayPause: FloatingActionButton
    private lateinit var tvNowPlaying: TextView

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPosition = -1
    private val audioList = mutableListOf<AudioFile>()
    private lateinit var audioAdapter: AudioAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply a colorful gradient background programmatically to avoid extra XML files
        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#E1BEE7"), Color.parseColor("#FCE4EC"))
        )
        window.decorView.background = gradientDrawable

        recyclerView = findViewById(R.id.recyclerView)
        progressIndicator = findViewById(R.id.progressIndicator)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)

        recyclerView.layoutManager = LinearLayoutManager(this)
        audioAdapter = AudioAdapter()
        recyclerView.adapter = audioAdapter

        btnPlayPause.setOnClickListener { togglePlayPause() }

        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            loadAudioFiles()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAudioFiles()
            } else {
                Toast.makeText(this, "Permission denied. Cannot load music.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAudioFiles() {
        progressIndicator.visibility = View.VISIBLE
        audioList.clear()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        contentResolver.query(collection, projection, null, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val artist = cursor.getString(artistColumn)
                val duration = cursor.getInt(durationColumn)

                // Filter out short system sounds (less than 10 seconds)
                if (duration > 10000) {
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    audioList.add(AudioFile(id, title, artist ?: "Unknown Artist", contentUri.toString()))
                }
            }
        }

        progressIndicator.visibility = View.GONE
        audioAdapter.notifyDataSetChanged()

        if (audioList.isEmpty()) {
            Toast.makeText(this, "No audio files found on device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudio(uri: String, position: Int) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, Uri.parse(uri))
            setOnPreparedListener {
                it.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                tvNowPlaying.text = "Now Playing: ${audioList[position].title}"
            }
            setOnCompletionListener {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                currentPlayingPosition = -1
                audioAdapter.notifyDataSetChanged()
            }
            prepareAsync()
        }
        currentPlayingPosition = position
        audioAdapter.notifyDataSetChanged()
    }

    private fun togglePlayPause() {
        if (currentPlayingPosition == -1) {
            if (audioList.isNotEmpty()) playAudio(audioList[0].uri, 0)
            return
        }

        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                it.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    data class AudioFile(val id: Long, val title: String, val artist: String, val uri: String)

    // Inner Adapter building UI programmatically to strictly avoid creating extra XML layout files
    inner class AudioAdapter : RecyclerView.Adapter<AudioAdapter.AudioViewHolder>() {
        
        inner class AudioViewHolder(view: View, val tvTitle: TextView, val tvArtist: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(48, 32, 48, 32)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }

            val tvTitle = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                textSize = 18f
                setTextColor(Color.parseColor("#311B92"))
                typeface = Typeface.DEFAULT_BOLD
            }

            val tvArtist = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                textSize = 14f
                setTextColor(Color.parseColor("#6A1B9A"))
            }

            layout.addView(tvTitle)
            layout.addView(tvArtist)

            return AudioViewHolder(layout, tvTitle, tvArtist)
        }

        override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
            val audio = audioList[position]
            holder.tvTitle.text = audio.title
            holder.tvArtist.text = audio.artist

            val isPlaying = position == currentPlayingPosition
            holder.itemView.alpha = if (isPlaying) 1.0f else 0.7f

            holder.itemView.setOnClickListener {
                if (isPlaying && mediaPlayer?.isPlaying == true) {
                    togglePlayPause()
                } else {
                    playAudio(audio.uri, position)
                }
            }
        }

        override fun getItemCount() = audioList.size
    }
}
