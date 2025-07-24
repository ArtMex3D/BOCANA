package com.cesar.bocana.ui.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.QrCodeOption
import java.text.SimpleDateFormat
import java.util.*

class LabelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labelView: View? = null

    fun updateView(data: LabelData, qrS: Bitmap?, qrM: Bitmap?) {
        labelView = createAndPopulateLabelView(context, data, qrS, qrM)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        labelView?.measure(widthMeasureSpec, heightMeasureSpec)
        val measuredWidth = labelView?.measuredWidth ?: 0
        val measuredHeight = labelView?.measuredHeight ?: 0
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        labelView?.layout(0, 0, right - left, bottom - top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        labelView?.draw(canvas)
    }

    // ##### INICIO DE LA FUNCIÓN MODIFICADA #####
    private fun createAndPopulateLabelView(context: Context, data: LabelData, qrS: Bitmap?, qrM: Bitmap?): View {
        val inflater = LayoutInflater.from(context)
        val simpleDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val view: View

        if (data.labelType == LabelType.DETAILED) {
            view = inflater.inflate(R.layout.layout_label_detailed, null)
            val productNameTv = view.findViewById<TextView>(R.id.label_product_name)
            // CAMBIO: Se obtienen las referencias a los nuevos TextViews separados
            val supplierTv = view.findViewById<TextView>(R.id.label_supplier)
            val dateTv = view.findViewById<TextView>(R.id.label_date)
            val weightTv = view.findViewById<TextView>(R.id.label_weight)
            // CAMBIO: Solo necesitamos la referencia al QR 'M'
            val qrMIv = view.findViewById<ImageView>(R.id.label_qr_m)

            productNameTv.text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"

            // CAMBIO: Se asigna el texto directamente a cada TextView sin prefijos
            supplierTv.text = data.supplierName ?: "PROVEEDOR"
            dateTv.text = simpleDateFormat.format(data.date)

            weightTv.text = if (data.weight == "Manual") "PESO: ____________ ${data.unit ?: ""}" else "PESO: ${data.weight} ${data.unit}"

            qrMIv.setImageBitmap(qrM)
            // CAMBIO: La visibilidad ahora solo depende del QR 'M'
            qrMIv.visibility = if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE

        } else { // LabelType.SIMPLE
            view = inflater.inflate(R.layout.layout_label_simple, null)
            val supplierTv = view.findViewById<TextView>(R.id.label_supplier_simple)
            val dateTv = view.findViewById<TextView>(R.id.label_date_simple)
            val qrSIv = view.findViewById<ImageView>(R.id.label_qr_s_simple)
            val qrMIv = view.findViewById<ImageView>(R.id.label_qr_m_simple)

            supplierTv.text = data.supplierName ?: "PROVEEDOR"
            dateTv.text = simpleDateFormat.format(data.date)

            qrSIv.setImageBitmap(qrS)
            qrMIv.setImageBitmap(qrM)
            qrSIv.visibility = if (data.qrCodeOption == QrCodeOption.STOCK_WEB || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE
            qrMIv.visibility = if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE
        }
        return view
    }
    // ##### FIN DE LA FUNCIÓN MODIFICADA #####
}