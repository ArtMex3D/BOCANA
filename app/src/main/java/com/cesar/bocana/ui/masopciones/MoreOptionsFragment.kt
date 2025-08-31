package com.cesar.bocana.ui.masopciones

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentMoreOptionsBinding
import com.cesar.bocana.ui.ajustes.AjustesFragment
import com.cesar.bocana.ui.archived.ArchivedProductsFragment
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.history.AdvancedHistoryFragment
import com.cesar.bocana.ui.history.HistoryFragment // <<<--- VOLVEMOS A IMPORTAR EL HISTORIAL ANTIGUO

class MoreOptionsFragment : Fragment() {

    private var _binding: FragmentMoreOptionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Botón para el historial simple (el original)
        binding.buttonHistory.setOnClickListener {
            val historyFragment = HistoryFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, historyFragment)
                .addToBackStack(null)
                .commit()
        }

        // Botón para la nueva búsqueda avanzada
        binding.buttonAdvancedHistory.setOnClickListener {
            val advancedHistoryFragment = AdvancedHistoryFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, advancedHistoryFragment)
                .addToBackStack(null)
                .commit()
        }

        // --- Resto de los botones sin cambios ---
        binding.buttonNavToAjustes.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, AjustesFragment())
                .addToBackStack("AjustesFragment")
                .commit()
        }

        binding.buttonNavToArchivedProducts.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, ArchivedProductsFragment())
                .addToBackStack("ArchivedProductsFragment")
                .commit()
        }

        binding.buttonNavToReportGenerator.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, com.cesar.bocana.ui.report.ReportConfigFragment())
                .addToBackStack("ReportConfigFragment")
                .commit()
        }

        // NUEVO: Listener para el botón de devoluciones
        binding.buttonNavToDevoluciones.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, DevolucionesFragment())
                .addToBackStack("DevolucionesFragment")
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}