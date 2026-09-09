package com.audiopro.djmrec.streaming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import android.text.TextUtils
import com.audiopro.djmrec.prolink.BannerPosition
import com.audiopro.djmrec.prolink.NowPlayingOptions
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

/** Burned into encoded video for both camera and artwork sources, not just the phone preview. */
internal object NowPlayingBanner {
    fun render(text: String, options: NowPlayingOptions, portrait: Boolean): BaseFilterRender {
        if (text.isBlank()) return NoFilterRender()
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 100 else 72
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (options.lightBackground) Color.rgb(245, 245, 245) else Color.rgb(20, 20, 20))
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (options.lightBackground) Color.BLACK else Color.WHITE
            textSize = if (portrait) 26f else 30f
        }
        val display = TextUtils.ellipsize(text, paint, width - 48f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(display, 24f, (height - paint.ascent() - paint.descent()) / 2f, paint)
        return ImageObjectFilterRender().apply {
            setImage(bitmap) // RootEncoder owns and recycles the bitmap after uploading it.
            val percent = height * 100f / if (portrait) 1280 else 720
            setScale(100f, percent)
            setPosition(0f, if (options.position == BannerPosition.TOP) 0f else 100f - percent)
        }
    }
}
