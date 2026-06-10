package com.cesar.bocana.ui.printing

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.PdfPage
import com.cesar.bocana.databinding.FragmentPdfViewerNativeBinding
import com.cesar.bocana.ui.adapters.PdfPageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerFragment : Fragment(), MenuProvider {

    private var _binding: FragmentPdfViewerNativeBinding? = null
    private val binding get() = _binding!!
    private var pdfFile: File? = null

    companion object {
        private const val ARG_PDF_PATH = "pdf_path"

        fun newInstance(pdfPath: String): PdfViewerFragment {
            return PdfViewerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PDF_PATH, pdfPath)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_PDF_PATH)?.let {
            pdfFile = File(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPdfViewerNativeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)


        binding.fabSharePdf.setOnClickListener { sharePdf() }

        if (pdfFile != null && pdfFile!!.exists()) {
            renderPdf()
        } else {
            Toast.makeText(context, "Error: No se pudo cargar el archivo PDF.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Previsualización de PDF"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun renderPdf() {
        lifecycleScope.launch {
            if (_binding == null) return@launch
            binding.progressBarPdf.visibility = View.VISIBLE
            try {
                val pages = withContext(Dispatchers.IO) {
                    val pageBitmaps = mutableListOf<PdfPage>()
                    val file = pdfFile ?: throw IllegalStateException("pdfFile no puede ser nulo aquí")
                    val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fileDescriptor)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        // Aumentamos la calidad de la renderización
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageBitmaps.add(PdfPage(i, bitmap))
                        page.close()
                    }
                    renderer.close()
                    fileDescriptor.close()
                    pageBitmaps
                }
                if (_binding == null) return@launch
                binding.pdfRecyclerView.adapter = PdfPageAdapter(pages)
            } catch (e: Exception) {
                val safeContext = activity?.applicationContext
                if (safeContext != null) {
                    Toast.makeText(safeContext, "Error al renderizar el PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (_binding != null) {
                    binding.progressBarPdf.visibility = View.GONE
                }

            }
        }
    }


    private fun sharePdf() {
        if (context == null || pdfFile == null || !pdfFile!!.exists()) {
            Toast.makeText(context, "No se puede compartir el archivo.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                pdfFile!!
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir Plan de Traspaso"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // No necesitamos un menú específico aquí
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        // Manejar el clic en el botón de "atrás" de la toolbar
        if (item.itemId == android.R.id.home) {
            parentFragmentManager.popBackStack()
            return true
        }
        return false
    }
}

