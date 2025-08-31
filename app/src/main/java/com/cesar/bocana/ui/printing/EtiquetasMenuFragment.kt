package com.cesar.bocana.ui.printing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
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
        // --- ETIQUETAS SIMPLES ---
        binding.cardEtiquetasSimples.setOnClickListener {
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

        // --- ETIQUETAS VARIABLES (HABILITADO) ---
        binding.cardEtiquetasVariablesX8.setOnClickListener {
            val template = LabelTemplates.detailedTemplates.find { it.description.contains("8 por hoja") }
            if (template != null) {
                navigateToMultiConfigScreen(template)
            }
        }
        binding.cardEtiquetasVariablesX6.setOnClickListener {
            val template = LabelTemplates.detailedTemplates.find { it.description.contains("6 por hoja") }
            if (template != null) {
                navigateToMultiConfigScreen(template)
            }
        }

        // --- PRÓXIMAMENTE ---
        binding.cardEditableSuperDetalle.setOnClickListener {
            Toast.makeText(context, "Esta función estará disponible próximamente.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToConfigScreen(type: LabelType, preselectedTemplate: LabelTemplate?) {
        val fragment = PrintLabelConfigFragment.newInstance(type, preselectedTemplate)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToMultiConfigScreen(template: LabelTemplate) {
        val fragment = PrintLabelMultiConfigFragment.newInstance(template)
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