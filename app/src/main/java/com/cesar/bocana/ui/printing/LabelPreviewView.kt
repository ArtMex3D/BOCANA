package com.cesar.bocana.ui.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.cesar.bocana.data.model.LabelData

/** La vista previa y el PDF usan exactamente el mismo renderer. */
class LabelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: LabelData? = null

    fun updateView(data: LabelData, qrS: Bitmap?, qrM: Bitmap?) {
        this.data = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = data ?: return
        LabelArtworkRenderer.draw(context, canvas, width.toFloat(), height.toFloat(), LabelArtworkRenderer.from(current))
    }
}
