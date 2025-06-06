package com.cesar.bocana.ui.printing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentPrintLabelMainBinding

enum class LabelType {
    SIMPLE, DETAILED
}

class PrintLabelMainFragment : Fragment() {

    private var _binding: FragmentPrintLabelMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrintLabelMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()

        binding.buttonPrintLabelContinue.setOnClickListener {
            val selectedRadioButtonId = binding.radioGroupLabelType.checkedRadioButtonId
            if (selectedRadioButtonId == -1) {
                Toast.makeText(context, "Selecciona un tipo de impresión", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val labelType = if (selectedRadioButtonId == binding.radioButtonSimplePrint.id) {
                LabelType.SIMPLE
            } else {
                LabelType.DETAILED
            }
            navigateToConfigScreen(labelType)
        }

        binding.buttonPrintLabelBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Manejar el botón de retroceso del sistema
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Imprimir Etiquetas - Paso 1"
            subtitle = null
            setDisplayHomeAsUpEnabled(true) // Muestra la flecha de regreso en el Toolbar
            // El manejo del clic en la flecha se hará en MainActivity o aquí si es necesario
        }
    }
    private fun navigateToConfigScreen(labelType: LabelType) {
        val fragment = PrintLabelConfigFragment.newInstance(labelType)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack("PrintLabelConfigFragment")
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Restaurar el título original del toolbar si es necesario,
        // o dejar que MainActivity lo maneje cuando este fragmento se quite.
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        _binding = null
    }
}