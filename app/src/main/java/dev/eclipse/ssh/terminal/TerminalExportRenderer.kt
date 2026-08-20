package dev.eclipse.ssh.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/** Creates a bounded terminal-style screenshot without exposing the rest of the app UI. */
object TerminalExportRenderer {
    fun renderScreenPng(title: String, text: String): ByteArray {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().takeLast(MAX_LINES)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(179, 193, 217)
            textSize = TEXT_SIZE
            typeface = Typeface.MONOSPACE
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(143, 211, 255)
            textSize = TITLE_SIZE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val maxLineWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        val width = (maxOf(titlePaint.measureText(title), maxLineWidth) + HORIZONTAL_PADDING * 2)
            .toInt()
            .coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = (TOP_PADDING + TITLE_LINE_HEIGHT + lines.size * LINE_HEIGHT + BOTTOM_PADDING)
            .coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(9, 13, 22))
        canvas.drawText(title, HORIZONTAL_PADDING, TOP_PADDING + TITLE_SIZE, titlePaint)
        val firstLineY = TOP_PADDING + TITLE_LINE_HEIGHT
        lines.forEachIndexed { index, line ->
            if (firstLineY + index * LINE_HEIGHT <= height - BOTTOM_PADDING) {
                canvas.drawText(line, HORIZONTAL_PADDING, (firstLineY + index * LINE_HEIGHT).toFloat(), textPaint)
            }
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private const val MAX_LINES = 120
    private const val MIN_WIDTH = 480
    private const val MAX_WIDTH = 2_048
    private const val MIN_HEIGHT = 240
    private const val MAX_HEIGHT = 4_096
    private const val HORIZONTAL_PADDING = 32f
    private const val TOP_PADDING = 28
    private const val BOTTOM_PADDING = 28
    private const val TITLE_SIZE = 24f
    private const val TEXT_SIZE = 22f
    private const val TITLE_LINE_HEIGHT = 48
    private const val LINE_HEIGHT = 32
}
