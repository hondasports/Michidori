package com.michidori.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class PlaybackActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: run {
            finish()
            return
        }
        val root = File(filesDir, RECORDINGS_DIRECTORY).canonicalFile
        val video = File(root, fileName).canonicalFile
        if (!video.exists() || !video.path.startsWith(root.path + File.separator)) {
            finish()
            return
        }
        val playerView = PlayerView(this)
        setContentView(playerView)
        player = ExoPlayer.Builder(this).build().also { activePlayer ->
            playerView.player = activePlayer
            activePlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            activePlayer.prepare()
            activePlayer.playWhenReady = true
        }
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }

    companion object {
        const val EXTRA_FILE_NAME = "fileName"
        private const val RECORDINGS_DIRECTORY = "recordings"
    }
}
