package com.cesar.bocana.ui.printing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.LabelData
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object LabelArtworkRenderer {
    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    data class Content(
        val type: LabelType,
        val productName: String = "",
        val supplierName: String = "",
        val dateText: String = "",
        val weight: String? = null,
        val unit: String = "",
        val detail: String? = null
    )

    fun from(data: LabelData): Content = Content(
        type = data.labelType,
        productName = data.productName.orEmpty(),
        supplierName = data.supplierName.orEmpty(),
        dateText = dateFormat.format(data.date),
        weight = data.weight,
        unit = data.unit.orEmpty(),
        detail = data.detail
    )

    fun from(config: IndividualLabelConfig): Content = Content(
        type = LabelType.DETAILED,
        productName = config.product.name,
        supplierName = config.supplierName,
        dateText = dateFormat.format(config.date),
        weight = config.weight,
        unit = config.unit,
        detail = config.detail
    )

    fun draw(context: Context, canvas: Canvas, width: Float, height: Float, content: Content) {
        if (width <= 1f || height <= 1f) return
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.drawColor(Color.WHITE)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = max(1.3f, min(width, height) * 0.010f)
        }
        val inset = border.strokeWidth / 2f

        when (content.type) {
            LabelType.SIMPLE -> drawSimple(canvas, width, height, content)
            LabelType.COSTAL -> drawCostal(canvas, width, height, content)
            LabelType.DETAILED -> drawDetailed(context, canvas, width, height, content)
        }

        canvas.drawRect(inset, inset, width - inset, height - inset, border)
        canvas.restore()
    }

    private fun drawSimple(canvas: Canvas, w: Float, h: Float, c: Content) {
        val pad = w * 0.065f
        val supplierArea = RectF(pad, h * 0.13f, w - pad, h * 0.64f)
        drawFitMultiline(canvas, c.supplierName.uppercase(Locale.ROOT), supplierArea, h * 0.26f, h * 0.11f, 2, true)
        drawFitSingle(canvas, c.dateText, RectF(pad, h * 0.68f, w - pad, h * 0.90f), h * 0.18f, h * 0.095f, true)
    }

    private fun drawCostal(canvas: Canvas, w: Float, h: Float, c: Content) {
        val pad = w * 0.06f
        drawFitMultiline(
            canvas,
            c.productName.uppercase(Locale.ROOT),
            RectF(pad, h * 0.08f, w - pad, h * 0.41f),
            h * 0.22f,
            h * 0.095f,
            2,
            true
        )
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(175, 175, 175)
            strokeWidth = max(0.9f, h * 0.005f)
        }
        canvas.drawLine(pad, h * 0.46f, w - pad, h * 0.46f, divider)
        canvas.drawLine(pad, h * 0.485f, w - pad, h * 0.485f, divider)
        drawFitSingle(canvas, c.supplierName.uppercase(Locale.ROOT), RectF(pad, h * 0.50f, w - pad, h * 0.68f), h * 0.13f, h * 0.072f, true)
        drawFitSingle(canvas, c.dateText, RectF(pad, h * 0.72f, w - pad, h * 0.90f), h * 0.13f, h * 0.072f, false)
    }

    private fun drawDetailed(context: Context, canvas: Canvas, w: Float, h: Float, c: Content) {
        val pad = w * 0.05f
        val footerTop = h * 0.885f
        val mainBottom = footerTop - h * 0.02f
        val logoSize = min(w, h) * 0.13f
        val logoUsed = drawOptionalLogo(context, canvas, pad, h * 0.055f, logoSize)
        val nameLeft = if (logoUsed) pad + logoSize + w * 0.03f else pad

        drawFitMultiline(
            canvas,
            c.productName.uppercase(Locale.ROOT),
            RectF(nameLeft, h * 0.05f, w - pad, h * 0.24f),
            h * 0.165f,
            h * 0.075f,
            2,
            true
        )

        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(135, 135, 135)
            strokeWidth = max(1f, h * 0.0046f)
        }

        canvas.drawLine(pad, h * 0.28f, w - pad, h * 0.28f, divider)
        canvas.drawLine(pad, h * 0.305f, w - pad, h * 0.305f, divider)

        val supplierDate = listOf(c.supplierName, c.dateText).filter { it.isNotBlank() }.joinToString("   ·   ")
        drawFitSingle(canvas, supplierDate, RectF(pad, h * 0.33f, w - pad, h * 0.47f), h * 0.088f, h * 0.05f, true)

        canvas.drawLine(pad, h * 0.51f, w - pad, h * 0.51f, divider)
        canvas.drawLine(pad, h * 0.535f, w - pad, h * 0.535f, divider)

        val detail = c.detail.orEmpty().trim()
        val unit = c.unit.uppercase(Locale.ROOT)
        if (c.weight == "Manual" || c.weight.isNullOrBlank()) {
            val y = h * 0.74f
            val lineEnd = w * 0.70f
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = max(1.2f, h * 0.0085f)
            }
            canvas.drawLine(pad, y, lineEnd, y, linePaint)
            drawFitSingle(canvas, unit, RectF(lineEnd + w * 0.03f, h * 0.62f, w - pad, mainBottom), h * 0.105f, h * 0.048f, true)
        } else {
            val weight = c.weight.orEmpty()
            val numberArea = RectF(pad, h * 0.57f, w * 0.69f, mainBottom)
            drawFitSingle(canvas, weight, numberArea, h * 0.245f, h * 0.11f, true)
            drawFitSingle(canvas, unit, RectF(w * 0.70f, h * 0.635f, w - pad, mainBottom), h * 0.10f, h * 0.045f, true)
        }

        val footerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 215, 215)
            style = Paint.Style.FILL
        }
        val footerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = max(0.9f, min(w, h) * 0.0045f)
        }
        canvas.drawRect(pad, footerTop, w - pad, h - pad * 0.4f, footerFill)
        canvas.drawRect(pad, footerTop, w - pad, h - pad * 0.4f, footerBorder)
        if (detail.isNotBlank()) {
            drawFitSingle(canvas, detail, RectF(pad + w * 0.02f, footerTop + h * 0.01f, w - pad - w * 0.02f, h - pad * 0.45f), h * 0.05f, h * 0.032f, false)
        }
    }

    private fun drawOptionalLogo(context: Context, canvas: Canvas, x: Float, y: Float, size: Float): Boolean {
        val resId = context.resources.getIdentifier("bocana_label_logo", "drawable", context.packageName)
        if (resId == 0) return false
        val drawable = ContextCompat.getDrawable(context, resId) ?: return false
        drawable.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        drawable.draw(canvas)
        return true
    }

    private fun drawFitSingle(canvas: Canvas, textRaw: String, area: RectF, maxSize: Float, minSize: Float, bold: Boolean) {
        val text = textRaw.trim()
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        var size = maxSize
        paint.textSize = size
        while (size > minSize && paint.measureText(text) > area.width()) {
            size -= max(0.7f, maxSize * 0.035f)
            paint.textSize = size
        }
        var finalText = text
        if (paint.measureText(finalText) > area.width()) {
            while (finalText.length > 3 && paint.measureText("$finalText…") > area.width()) finalText = finalText.dropLast(1)
            finalText += "…"
        }
        val fm = paint.fontMetrics
        val baseline = area.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(finalText, area.centerX(), baseline, paint)
    }

    private fun drawFitMultiline(
        canvas: Canvas,
        textRaw: String,
        area: RectF,
        maxSize: Float,
        minSize: Float,
        maxLines: Int,
        bold: Boolean
    ) {
        val text = textRaw.trim()
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        var size = maxSize
        var lines: List<String>
        while (true) {
            paint.textSize = size
            lines = wrap(text, paint, area.width(), maxLines)
            val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.02f
            if ((lines.size <= maxLines && lines.all { paint.measureText(it) <= area.width() } && lineHeight * lines.size <= area.height()) || size <= minSize) break
            size -= max(0.7f, maxSize * 0.04f)
        }
        if (lines.size > maxLines) lines = lines.take(maxLines)
        val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.02f
        var y = area.centerY() - ((lines.size - 1) * lineHeight / 2f) - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        lines.forEach { line ->
            var safe = line
            while (safe.length > 3 && paint.measureText(safe) > area.width()) safe = safe.dropLast(1)
            if (safe != line) safe += "…"
            canvas.drawText(safe, area.centerX(), y, paint)
            y += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = words.first()
        for (i in 1 until words.size) {
            val candidate = "$current ${words[i]}"
            if (paint.measureText(candidate) <= maxWidth || lines.size == maxLines - 1) {
                current = candidate
            } else {
                lines += current
                current = words[i]
            }
        }
        lines += current
        return lines
    }
}
