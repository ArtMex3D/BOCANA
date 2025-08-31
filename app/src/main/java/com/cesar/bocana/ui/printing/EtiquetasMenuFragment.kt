package com.cesar.bocana.ui.printing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentEtiquetasMenuBinding

class EtiquetasMenuFragment : Fragment() {

    private var _binding: FragmentEtiquetasMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEtiquetasMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Impresión de Etiquetas"
            subtitle = "Selecciona una opción"
            setDisplayHomeAsUpEnabled(false)
        }
    }

    private fun setupListeners() {
        // --- ETIQUETAS SIMPLES (CORREGIDO) ---
        binding.cardEtiquetasSimples.setOnClickListener {
            // CORRECCIÓN: Navegar a la pantalla de configuración PRIMERO,
            // sin una plantilla preseleccionada, para que el usuario ingrese los datos.
            navigateToConfigScreen(LabelType.SIMPLE, null)
        }

        // --- ETIQUETAS DETALLADAS FIJAS ---
        binding.cardFijasx8.setOnClickListener {
            val template = LabelTemplates.detailedTemplates.find { it.description.contains("8 por hoja") }
            if (template != null) {
                navigateToConfigScreen(LabelType.DETAILED, template)
            }
        }

        binding.cardFijasx6.setOnClickListener {
            val template = LabelTemplates.detailedTemplates.find { it.description.contains("6 por hoja") }
            if (template != null) {
                navigateToConfigScreen(LabelType.DETAILED, template)
            }
        }

        // --- ETIQUETAS VARIABLES (FUTURO) ---
        val proximamenteListener = View.OnClickListener {
            Toast.makeText(context, "Esta función estará disponible próximamente.", Toast.LENGTH_SHORT).show()
        }
        binding.cardEtiquetasVariablesX8.setOnClickListener(proximamenteListener)
        binding.cardEtiquetasVariablesX6.setOnClickListener(proximamenteListener)
        binding.cardEditableSuperDetalle.setOnClickListener(proximamenteListener)
    }

    private fun navigateToConfigScreen(type: LabelType, preselectedTemplate: LabelTemplate?) {
        val fragment = PrintLabelConfigFragment.newInstance(type, preselectedTemplate)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    // Esta función ya no es llamada directamente desde el menú de etiquetas simples.
    private fun navigateToLayoutSelectionScreen(type: LabelType) {
        val data = LabelData(labelType = type)
        val fragment = PrintLabelLayoutFragment.newInstance(data)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}