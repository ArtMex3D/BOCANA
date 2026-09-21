package com.cesar.bocana.ui.printing

import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cesar.bocana.R

object LabelEditRouter {
    fun open(fragment: Fragment, recordId: String) {
        val context = fragment.context ?: return
        val record = ActiveLabelStore.get(context, recordId) ?: run {
            Toast.makeText(context, "La etiqueta ya no está disponible.", Toast.LENGTH_SHORT).show()
            return
        }
        val template = LabelTemplates.findByDescription(record.templateDescription) ?: run {
            Toast.makeText(context, "No se encontró el formato de la etiqueta.", Toast.LENGTH_SHORT).show()
            return
        }
        val target: Fragment = when (record.flowType) {
            LabelFlowType.SIMPLE, LabelFlowType.COSTAL, LabelFlowType.FIXED_DETAILED -> {
                val data = ActiveLabelStore.decodeLabelData(record) ?: return
                PrintLabelConfigFragment.newInstance(data.labelType, template, data, record.id)
            }
            LabelFlowType.VARIABLE_DETAILED -> {
                PrintLabelMultiConfigFragment.newInstance(template, record.id)
            }
        }
        fragment.parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, target)
            .addToBackStack(null)
            .commit()
    }
}
