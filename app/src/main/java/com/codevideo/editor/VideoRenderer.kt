package com.codevideo.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import org.json.JSONObject
import java.io.File

private class TimedTextOverlay(
    private val text: SpannableString,
    private val startUs: Long,
    private val endUs: Long,
    private val shown: OverlaySettings,
    private val hidden: OverlaySettings
) : TextOverlay() {

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs in startUs..endUs) shown else hidden
}

class VideoRenderer(private val context: Context) {

    fun render(
        inputUri: Uri,
        editsJson: String,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val edits = JSONObject(editsJson)

            val itemBuilder = MediaItem.Builder().setUri(inputUri)
            val trim = edits.optJSONObject("trim")
            if (trim != null) {
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((trim.optDouble("start", 0.0) * 1000).toLong())
                if (trim.has("end")) {
                    clip.setEndPositionMs((trim.getDouble("end") * 1000).toLong())
                }
                itemBuilder.setClippingConfiguration(clip.build())
            }

            val audioProcessors = mutableListOf<AudioProcessor>()
            val videoEffects = mutableListOf<Effect>()

            val speed = edits.optDouble("speed", 1.0).toFloat()
            if (speed <= 0f) throw IllegalArgumentException("speed 0 से बड़ी होनी चाहिए")
            if (speed != 1f) {
                val sonic = SonicAudioProcessor()
                sonic.setSpeed(speed)
                audioProcessors.add(sonic)
                videoEffects.add(SpeedChangeEffect(speed))
            }

            val overlays = mutableListOf<TextureOverlay>()
            val texts = edits.optJSONArray("texts")
            if (texts != null) {
                for (i in 0 until texts.length()) {
                    val t = texts.getJSONObject(i)
                    val content = t.optString("text", "")
                    if (content.isEmpty()) continue

                    val x = t.optDouble("x", 50.0).toFloat()
                    val y = t.optDouble("y", 50.0).toFloat()
                    val size = t.optInt("size", 72)
                    val startUs = (t.optDouble("start", 0.0) * 1_000_000).toLong()
                    val endUs = (t.optDouble("end", 3600.0) * 1_000_000).toLong()

                    val span = SpannableString(content)
                    span.setSpan(
                        ForegroundColorSpan(Color.WHITE),
                        0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    span.setSpan(
                        AbsoluteSizeSpan(size),
                        0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    val nx = x / 100f * 2f - 1f
                    val ny = 1f - y / 100f * 2f
                    val shown = OverlaySettings.Builder()
                        .setBackgroundFrameAnchor(nx, ny)
                        .setOverlayFrameAnchor(0f, 0f)
                        .build()
                    val hidden = OverlaySettings.Builder().setBackgroundFrameAnchor(nx, ny).setOverlayFrameAnchor(0f, 0f).setAlphaScale(0f).build()

                    overlays.add(TimedTextOverlay(span, startUs, endUs, shown, hidden))
                }
            }
            if (overlays.isNotEmpty()) {
                videoEffects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
            }

            val editedItem = EditedMediaItem.Builder(itemBuilder.build())
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()

            val outFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        Thread {
                            try {
                                val where = saveToGallery(outFile)
                                outFile.delete()
                                onDone("एक्सपोर्ट पूरा: $where")
                            } catch (e: Exception) {
                                onError(e.message ?: "वीडियो सेव नहीं हुआ")
                            }
                        }.start()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        onError(exportException.message ?: "एक्सपोर्ट फ़ेल हुआ")
                    }
                })
                .build()

            transformer.start(editedItem, outFile.absolutePath)
        } catch (e: Exception) {
            onError(e.message ?: "अज्ञात गड़बड़ी")
        }
    }

    private fun saveToGallery(file: File): String {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CodeVideoEditor")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("गैलरी में फ़ाइल नहीं बन सकी")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return "Movies/CodeVideoEditor/${file.name}"
    }
}
