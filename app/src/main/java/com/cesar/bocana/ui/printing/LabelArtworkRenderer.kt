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
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = max(1.8f, min(width, height) * 0.015f)
        }
        val inset = border.strokeWidth / 2f

        when (content.type) {
            LabelType.SIMPLE -> drawSimple(context, canvas, width, height, content)
            LabelType.COSTAL -> drawCostal(context, canvas, width, height, content)
            LabelType.DETAILED -> drawDetailed(context, canvas, width, height, content)
        }

        canvas.drawRect(inset, inset, width - inset, height - inset, border)
        canvas.restore()
    }

    private fun drawSimple(context: Context, canvas: Canvas, w: Float, h: Float, c: Content) {
        val pad = w * 0.065f
        val logoSize = min(w, h) * 0.16f
        val logoUsed = drawOptionalLogo(context, canvas, pad, h * 0.06f, logoSize)
        val contentTop = if (logoUsed) h * 0.22f else h * 0.13f
        val supplierArea = RectF(pad, contentTop, w - pad, h * 0.64f)
        drawFitMultiline(canvas, c.supplierName.uppercase(Locale.ROOT), supplierArea, h * 0.25f, h * 0.11f, 2, true)
        drawFitSingle(canvas, c.dateText, RectF(pad, h * 0.69f, w - pad, h * 0.91f), h * 0.18f, h * 0.095f, true)
    }

    private fun drawCostal(context: Context, canvas: Canvas, w: Float, h: Float, c: Content) {
        val pad = w * 0.06f
        val logoSize = min(w, h) * 0.14f
        val logoUsed = drawOptionalLogo(context, canvas, pad, h * 0.07f, logoSize)
        val nameLeft = if (logoUsed) pad + logoSize + w * 0.03f else pad
        drawFitMultiline(
            canvas,
            c.productName.uppercase(Locale.ROOT),
            RectF(nameLeft, h * 0.08f, w - pad, h * 0.30f),
            h * 0.19f,
            h * 0.09f,
            2,
            true
        )
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 95
            strokeWidth = max(1.1f, h * 0.005f)
        }
        canvas.drawLine(pad, h * 0.39f, w - pad, h * 0.39f, divider)
        canvas.drawLine(pad, h * 0.415f, w - pad, h * 0.415f, divider)
        drawFitSingle(canvas, c.supplierName.uppercase(Locale.ROOT), RectF(pad, h * 0.44f, w - pad, h * 0.62f), h * 0.12f, h * 0.068f, true)
        drawFitSingle(canvas, c.dateText, RectF(pad, h * 0.70f, w - pad, h * 0.88f), h * 0.13f, h * 0.072f, false)
    }

    private fun drawDetailed(context: Context, canvas: Canvas, w: Float, h: Float, c: Content) {
        val outerPad = w * 0.045f
        val left = outerPad
        val right = w - outerPad
        val top = h * 0.06f
        val footerTop = h * 0.86f
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 170
            strokeWidth = max(1.2f, min(w, h) * 0.006f)
        }

        val logoSize = min(w, h) * 0.16f
        val headerTop = top
        val headerBottom = h * 0.23f
        val logoX = left + w * 0.015f
        val logoY = headerTop + (headerBottom - headerTop - logoSize) / 2f
        val logoUsed = drawOptionalLogo(context, canvas, logoX, logoY, logoSize)
        val separatorX = if (logoUsed) logoX + logoSize + w * 0.025f else left + w * 0.02f
        if (logoUsed) {
            canvas.drawLine(separatorX, headerTop + h * 0.01f, separatorX, headerBottom - h * 0.01f, dividerPaint)
        }
        val nameArea = RectF(separatorX + w * 0.03f, headerTop, right - w * 0.015f, headerBottom)
        drawFitMultiline(
            canvas,
            c.productName.uppercase(Locale.ROOT),
            nameArea,
            h * 0.18f,
            h * 0.09f,
            2,
            true
        )

        val stripTop = h * 0.275f
        val stripBottom = h * 0.43f
        canvas.drawLine(left, stripTop, right, stripTop, dividerPaint)
        canvas.drawLine(left, stripBottom, right, stripBottom, dividerPaint)
        val supplierDate = listOf(c.supplierName, c.dateText).filter { it.isNotBlank() }.joinToString("  ·  ")
        drawFitSingle(
            canvas,
            supplierDate,
            RectF(left + w * 0.02f, h * 0.31f, right - w * 0.02f, h * 0.40f),
            h * 0.095f,
            h * 0.05f,
            true
        )

        val detail = c.detail.orEmpty().trim()
        val unit = c.unit.uppercase(Locale.ROOT)
        if (c.weight == "Manual" || c.weight.isNullOrBlank()) {
            val lineY = h * 0.77f
            val lineEnd = w * 0.72f
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = max(1.8f, h * 0.010f)
            }
            canvas.drawLine(left, lineY, lineEnd, lineY, linePaint)
            drawFitSingle(
                canvas,
                unit,
                RectF(lineEnd + w * 0.03f, h * 0.66f, right, h * 0.84f),
                h * 0.12f,
                h * 0.05f,
                true
            )
        } else {
            val weight = c.weight.orEmpty()
            drawFitSingle(
                canvas,
                weight,
                RectF(left, h * 0.47f, w * 0.63f, h * 0.80f),
                h * 0.33f,
                h * 0.17f,
                true
            )
            drawFitSingle(
                canvas,
                unit,
                RectF(w * 0.66f, h * 0.57f, right, h * 0.78f),
                h * 0.115f,
                h * 0.05f,
                true
            )
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawRect(left, footerTop, right, h - outerPad, footerPaint)
        val footerText = if (detail.isNotBlank()) detail.uppercase(Locale.ROOT) else "PRODUCTO DEL MAR"
        drawFitSingleWhite(
            canvas,
            footerText,
            RectF(left + w * 0.02f, footerTop + h * 0.005f, right - w * 0.02f, h - outerPad),
            h * 0.052f,
            h * 0.032f,
            false
        )
    }

    private fun drawOptionalLogo(context: Context, canvas: Canvas, x: Float, y: Float, size: Float): Boolean {
        val candidates = listOf("bocana_label_logo_black", "bocana_label_logo", "ic_bocana_label_logo")
        val resId = candidates.firstNotNullOfOrNull { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) id else null
        } ?: return false
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

    private fun drawFitSingleWhite(canvas: Canvas, textRaw: String, area: RectF, maxSize: Float, minSize: Float, bold: Boolean) {
        val text = textRaw.trim()
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        var size = maxSize
        paint.textSize = size
        while (size > minSize && paint.measureText(text) > area.width()) {
            size -= max(0.5f, maxSize * 0.03f)
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
