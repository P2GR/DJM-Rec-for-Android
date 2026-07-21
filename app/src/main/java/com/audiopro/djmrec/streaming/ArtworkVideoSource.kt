package com.audiopro.djmrec.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.graphics.SurfaceTexture
import android.view.Surface
import com.pedro.encoder.input.sources.video.VideoSource
import kotlin.math.min

/** User-selected still image source used when DJ only wants to broadcast mixer audio. */
class ArtworkVideoSource(
    private val context: Context,
    artworkUri: String,
    private val onFailure: (String) -> Unit
) : VideoSource() {
    private val artworkUri = Uri.parse(artworkUri)
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var surface: Surface? = null
    private var artwork: Bitmap? = null

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        artwork = runCatching { decodeArtwork(width, height) }.getOrNull()
        return artwork != null
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        if (running) return
        this.surfaceTexture = surfaceTexture
        surfaceTexture.setDefaultBufferSize(width, height)
        surface = Surface(surfaceTexture)
        if (artwork == null) {
            onFailure("Selected artwork could not be opened")
            return
        }
        running = true
        worker = Thread({
            val frameDelay = (1_000L / fps.coerceAtLeast(1)).coerceAtLeast(1)
            var consecutiveFailures = 0
            while (running) {
                val target = surface
                val frame = artwork
                var canvas: Canvas? = null
                try {
                    canvas = target?.lockCanvas(null)
                    if (canvas != null && frame != null) canvas.drawBitmap(frame, 0f, 0f, null)
                    consecutiveFailures = 0
                } catch (_: Exception) {
                    consecutiveFailures++
                } finally {
                    if (canvas != null) runCatching { target?.unlockCanvasAndPost(canvas) }
                }
                if (consecutiveFailures >= 3) {
                    running = false
                    onFailure("Phone could not render livestream artwork")
                    break
                }
                try {
                    Thread.sleep(frameDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }, "DjmLiveArtwork").apply { start() }
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
        surface?.release()
        surface = null
    }

    override fun release() {
        stop()
        artwork?.recycle()
        artwork = null
    }

    override fun isRunning(): Boolean = running

    private fun decodeArtwork(width: Int, height: Int): Bitmap {
        require(artworkUri != Uri.EMPTY) { "Artwork URI is missing" }
        val source = ImageDecoder.createSource(context.contentResolver, artworkUri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestSide = maxOf(info.size.width, info.size.height)
            val targetLongestSide = maxOf(width, height) * 2
            decoder.setTargetSampleSize((longestSide / targetLongestSide).coerceAtLeast(1))
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = min(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
        val drawnWidth = decoded.width * scale
        val drawnHeight = decoded.height * scale
        val destination = RectF(
            (width - drawnWidth) / 2f,
            (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f,
            (height + drawnHeight) / 2f
        )
        canvas.drawBitmap(decoded, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return output
    }
}
