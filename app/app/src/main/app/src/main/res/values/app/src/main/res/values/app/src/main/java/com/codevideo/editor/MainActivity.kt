package com.codevideo.editor

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            videoUri = uri
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            sendStatus("वीडियो चुन लिया गया")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView = PlayerView(this)
        playerView.player = exo

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(0xFF080808.toInt())
        webView.addJavascriptInterface(ScriptBridge(this), "Android")
        webView.loadUrl("file:///android_asset/editor.html")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.addView(
            playerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    fun pickVideo() {
        runOnUiThread { pickLauncher.launch("video/*") }
    }

    fun runEdits(editsJson: String) {
        runOnUiThread {
            val uri = videoUri
            if (uri == null) {
                sendStatus("पहले वीडियो चुनें")
            } else {
                sendStatus("एक्सपोर्ट शुरू हो रहा है...")
                VideoRenderer(this).render(
                    inputUri = uri,
                    editsJson = editsJson,
                    onDone = { sendStatus(it) },
                    onError = { sendStatus("गड़बड़ी: $it") }
                )
            }
        }
    }

    fun sendStatus(msg: String) {
        runOnUiThread {
            webView.evaluateJavascript("onStatus(${JSONObject.quote(msg)})", null)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView.destroy()
        super.onDestroy()
    }
}
