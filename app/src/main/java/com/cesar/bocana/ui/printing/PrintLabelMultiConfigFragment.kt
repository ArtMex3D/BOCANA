package com.cesar.bocana.ui.printing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.databinding.FragmentPrintLabelMultiConfigBinding
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PrintLabelMultiConfigFragment : Fragment(), AssignLabelDialogFragment.OnLabelConfiguredListener {

    private var _binding: FragmentPrintLabelMultiConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var selectedTemplate: LabelTemplate
    private lateinit var labelSlots: MutableList<IndividualLabelConfig?>
    private lateinit var adapter: LabelSlotAdapter

    companion object {
        private const val ARG_TEMPLATE = "template_arg"
        fun newInstance(template: LabelTemplate): PrintLabelMultiConfigFragment {
            return PrintLabelMultiConfigFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_TEMPLATE, template)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            selectedTemplate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_TEMPLATE, LabelTemplate::class.java)!!
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(ARG_TEMPLATE)!!
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintLabelMultiConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()

        labelSlots = MutableList(selectedTemplate.totalLabels) { null }
        setupRecyclerView()
        setupClickListeners()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Configurar Hoja Variable"
            subtitle = selectedTemplate.description
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupRecyclerView() {
        adapter = LabelSlotAdapter(
            labelSlots,
            onAssign = { position -> showAssignDialog(position, null) },
            onEdit = { position -> showAssignDialog(position, labelSlots[position]) },
            onDelete = { position -> deleteSlot(position) }
        )
        binding.recyclerViewLabelSlots.adapter = adapter
        binding.textViewTemplateTitle.text = "Configurando Hoja: ${selectedTemplate.description}"
    }

    private fun showAssignDialog(position: Int, existingConfig: IndividualLabelConfig?) {
        val dialog = AssignLabelDialogFragment.newInstance(position, existingConfig)
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, "AssignLabelDialog")
    }

    private fun deleteSlot(position: Int) {
        labelSlots[position] = null
        adapter.notifyItemChanged(position)
        updateGenerateButtonState()
    }

    override fun onLabelConfigured(position: Int, config: IndividualLabelConfig, copies: Int) {
        var slotsFilled = 0
        for (i in 0 until copies) {
            val targetPosition = position + i
            if (targetPosition < labelSlots.size) {
                labelSlots[targetPosition] = config
                slotsFilled++
            } else {
                Toast.makeText(context, "No hay más espacio en la hoja.", Toast.LENGTH_SHORT).show()
                break
            }
        }
        if (slotsFilled > 0) {
            adapter.notifyItemRangeChanged(position, slotsFilled)
        }
        updateGenerateButtonState()
    }

    private fun updateGenerateButtonState() {
        binding.fabGenerateMultiPdf.isEnabled = labelSlots.any { it != null }
    }

    private fun setupClickListeners() {
        binding.fabGenerateMultiPdf.setOnClickListener {
            generatePdf()
        }
    }

    private fun generatePdf() {
        val currentContext = context ?: return
        binding.progressBarMultiConfig.isVisible = true
        binding.fabGenerateMultiPdf.isEnabled = false
        Toast.makeText(context, "Generando PDF...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    PdfGenerator.createMultiLabelPdf(currentContext, labelSlots, selectedTemplate)
                }
                sharePdf(currentContext, pdfFile)
            } catch (e: Exception) {
                Log.e("MultiPDF_CRASH", "Error al generar PDF múltiple", e)
                showErrorDialog(currentContext, e)
            } finally {
                if(isAdded && _binding != null) {
                    binding.progressBarMultiConfig.isVisible = false
                    updateGenerateButtonState()
                }
            }
        }
    }

    private fun sharePdf(context: Context, file: File) {
        if (!isAdded || !file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir etiquetas PDF..."))
        } catch (e: Exception) {
            Log.e("SharePdf", "Error al compartir PDF", e)
            Toast.makeText(context, "No se pudo compartir el archivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorDialog(context: Context, e: Exception) {
        if (!isAdded) return
        AlertDialog.Builder(context)
            .setTitle("¡Oops! Ocurrió un error")
            .setMessage(e.stackTraceToString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}