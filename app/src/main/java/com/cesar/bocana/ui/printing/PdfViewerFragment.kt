package com.cesar.bocana.ui.printing

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.PdfPage
import com.cesar.bocana.databinding.FragmentPdfViewerNativeBinding
import com.cesar.bocana.ui.adapters.PdfPageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Visor común: Traspasos, Etiquetas y futuros PDFs. */
class PdfViewerFragment : Fragment(), MenuProvider {

    private var _binding: FragmentPdfViewerNativeBinding? = null
    private val binding get() = _binding!!
    private var pdfFile: File? = null
    private var activeLabelId: String? = null
    private var customTitle: String? = null

    companion object {
        private const val ARG_PDF_PATH = "pdf_path"
        private const val ARG_ACTIVE_LABEL_ID = "active_label_id"
        private const val ARG_TITLE = "viewer_title"

        fun newInstance(pdfPath: String, activeLabelId: String? = null, title: String? = null) = PdfViewerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PDF_PATH, pdfPath)
                activeLabelId?.let { putString(ARG_ACTIVE_LABEL_ID, it) }
                title?.let { putString(ARG_TITLE, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfFile = arguments?.getString(ARG_PDF_PATH)?.let(::File)
        activeLabelId = arguments?.getString(ARG_ACTIVE_LABEL_ID)
        customTitle = arguments?.getString(ARG_TITLE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPdfViewerNativeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = customTitle ?: "Previsualización de PDF"
            subtitle = if (activeLabelId != null) "Etiqueta lista para imprimir" else null
            setDisplayHomeAsUpEnabled(true)
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val isLabel = activeLabelId != null
        binding.buttonModifyLabel.isVisible = isLabel
        binding.buttonDeleteLabel.isVisible = isLabel
        binding.buttonSharePdf.setOnClickListener { sharePdf() }
        binding.buttonModifyLabel.setOnClickListener { activeLabelId?.let { LabelEditRouter.open(this, it) } }
        binding.buttonDeleteLabel.setOnClickListener { confirmDelete() }

        if (pdfFile?.exists() == true) renderPdf() else Toast.makeText(context, "No se pudo cargar el PDF.", Toast.LENGTH_LONG).show()
    }

    private fun renderPdf() {
        lifecycleScope.launch {
            binding.progressBarPdf.isVisible = true
            try {
                val pages = withContext(Dispatchers.IO) {
                    val result = mutableListOf<PdfPage>()
                    val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(descriptor)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result += PdfPage(i, bitmap)
                        page.close()
                    }
                    renderer.close()
                    descriptor.close()
                    result
                }
                if (_binding != null) binding.pdfRecyclerView.adapter = PdfPageAdapter(pages)
            } catch (e: Exception) {
                Toast.makeText(context, "Error al renderizar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) binding.progressBarPdf.isVisible = false
            }
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        if (!file.exists()) return
        try {
            val uri: Uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Imprimir o compartir PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val id = activeLabelId ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar etiqueta")
            .setMessage("Esta copia dejará de aparecer en Etiquetas activas.")
            .setPositiveButton("Eliminar") { _, _ ->
                ActiveLabelStore.delete(requireContext(), id)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, EtiquetasMenuFragment())
                    .commit()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = Unit

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            parentFragmentManager.popBackStack()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        _binding = null
    }
}
