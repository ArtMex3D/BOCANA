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

        binding.buttonNavToPrintLabels.setOnClickListener {
            // Lógica futura para imprimir etiquetas
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
