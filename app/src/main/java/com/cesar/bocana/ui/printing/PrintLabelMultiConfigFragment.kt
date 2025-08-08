package com.cesar.bocana.ui.printing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cesar.bocana.R

/**
 * Placeholder (Archivo base) para el fragmento de configuración de etiquetas múltiples.
 * Esto es necesario para que el proyecto compile mientras desarrollamos el paso 5.
 */
class PrintLabelMultiConfigFragment : Fragment() {

    companion object {
        fun newInstance(): PrintLabelMultiConfigFragment {
            return PrintLabelMultiConfigFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflar un layout temporal o básico
        return inflater.inflate(R.layout.fragment_more_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Lógica futura irá aquí
    }
}