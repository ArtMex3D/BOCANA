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


class PrintLabelMainFragment : Fragment() {

    private var _binding: FragmentPrintLabelMainBinding? = null
    private val binding get() = _binding!!
    private var flowType: LabelFlowType? = null

    companion object {
        private const val ARG_FLOW_TYPE = "flow_type"

        fun newInstance(flowType: LabelFlowType): PrintLabelMainFragment {
            val args = Bundle()
            args.putSerializable(ARG_FLOW_TYPE, flowType)
            val fragment = PrintLabelMainFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            flowType = it.getSerializable(ARG_FLOW_TYPE) as? LabelFlowType
        }
    }

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

        when (flowType) {
            LabelFlowType.SIMPLE -> navigateToConfigScreen(LabelType.SIMPLE)
            LabelFlowType.FIXED_DETAILED -> navigateToConfigScreen(LabelType.DETAILED)
            else -> setupSelectionScreen()
        }

        binding.buttonPrintLabelBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupSelectionScreen() {
        binding.radioGroupLabelType.visibility = View.VISIBLE
        binding.textViewSelectLabelType.visibility = View.VISIBLE
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
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Imprimir Etiquetas"
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
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
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        _binding = null
    }
}