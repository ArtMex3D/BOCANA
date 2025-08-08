package com.cesar.bocana.ui.printing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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

        binding.cardEtiquetasSimples.setOnClickListener {
            val fragment = PrintLabelMainFragment.newInstance(LabelFlowType.SIMPLE)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, fragment)
                .addToBackStack(null)
                .commit()
        }

        binding.cardEtiquetasFijas.setOnClickListener {
            val fragment = PrintLabelMainFragment.newInstance(LabelFlowType.FIXED_DETAILED)
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, fragment)
                .addToBackStack(null)
                .commit()
        }

        binding.cardEtiquetasVariables.setOnClickListener {
            val fragment = PrintLabelMultiConfigFragment.newInstance()
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, fragment)
                .addToBackStack(null)
                .commit()
        }

        val proximamenteListener = View.OnClickListener {
            Toast.makeText(context, "Esta función estará disponible próximamente.", Toast.LENGTH_SHORT).show()
        }
        binding.cardQr1.setOnClickListener(proximamenteListener)
        binding.cardQr2.setOnClickListener(proximamenteListener)
        binding.cardQr3.setOnClickListener(proximamenteListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}