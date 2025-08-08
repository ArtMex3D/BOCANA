package com.cesar.bocana.ui.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import java.text.SimpleDateFormat
import java.util.*

class LabelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labelView: View? = null

    fun updateView(data: LabelData, qrS: Bitmap?, qrM: Bitmap?) {
        labelView = createAndPopulateLabelView(context, data)
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

    private fun createAndPopulateLabelView(context: Context, data: LabelData): View {
        val inflater = LayoutInflater.from(context)
        val simpleDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val view: View

        when (data.labelType) {
            LabelType.DETAILED -> {
                view = inflater.inflate(R.layout.layout_label_detailed_v2, null)
                view.findViewById<TextView>(R.id.label_product_name).text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"
                view.findViewById<TextView>(R.id.label_supplier).text = data.supplierName ?: "PROVEEDOR"
                view.findViewById<TextView>(R.id.label_date).text = simpleDateFormat.format(data.date)
                val detailTv = view.findViewById<TextView>(R.id.label_detail)
                detailTv.text = data.detail ?: ""
                detailTv.visibility = if (data.detail.isNullOrBlank()) View.GONE else View.VISIBLE

                // Lógica para mostrar el layout de peso correcto
                val predefinedWeightLayout = view.findViewById<TextView>(R.id.label_weight_predefined)
                val manualWeightLayout = view.findViewById<ViewGroup>(R.id.layout_weight_manual)

                if (data.weight == "Manual") {
                    predefinedWeightLayout.visibility = View.GONE
                    manualWeightLayout.visibility = View.VISIBLE
                    manualWeightLayout.findViewById<TextView>(R.id.label_weight_manual_unit).text = data.unit ?: ""
                } else {
                    predefinedWeightLayout.visibility = View.VISIBLE
                    manualWeightLayout.visibility = View.GONE
                    predefinedWeightLayout.text = "PESO: ${data.weight ?: ""} ${data.unit ?: ""}"
                }
            }
            LabelType.SIMPLE -> {
                view = inflater.inflate(R.layout.layout_label_simple, null)
                view.findViewById<TextView>(R.id.label_supplier_simple).text = data.supplierName ?: "PROVEEDOR"
                view.findViewById<TextView>(R.id.label_date_simple).text = simpleDateFormat.format(data.date)
            }
        }
        return view
    }
}