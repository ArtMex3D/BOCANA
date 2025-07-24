package com.cesar.bocana.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.InventoryRepository
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.databinding.FragmentHistoryBinding
import com.cesar.bocana.ui.adapters.StockMovementAdapter
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var movementAdapter: StockMovementAdapter
    private lateinit var repository: InventoryRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        val db = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(db, Firebase.firestore)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Historial de Movimientos"
        setupRecyclerView()
        loadMovements()
    }

    private fun setupRecyclerView() {
        movementAdapter = StockMovementAdapter(repository, viewLifecycleOwner.lifecycleScope)
        binding.historyRecyclerView.adapter = movementAdapter
    }

    private fun loadMovements() {
        lifecycleScope.launch {
            binding.progressBar.isVisible = true
            try {
                repository.syncNewData()
                Log.d("HistoryFragment", "Sincronización inteligente completada.")

                // CORREGIDO: Se pasa 'null' para el nuevo parámetro 'lotIds'
                repository.getFilteredMovementsPaged(null, null, null, null, null, null, null, lotIds = null)
                    .collectLatest { pagingData ->
                        if (isAdded) {
                            movementAdapter.submitData(pagingData)
                        }
                    }

                if(isAdded) {
                    binding.emptyView.isVisible = movementAdapter.itemCount == 0
                }

            } catch (e: Exception) {
                if (isAdded) showErrorDialog("Error al cargar movimientos", e)
            } finally {
                if(isAdded) binding.progressBar.isVisible = false
            }
        }
    }

    private fun showErrorDialog(title: String, e: Exception) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(e.stackTraceToString())
            .setPositiveButton("Copiar y Cerrar") { dialog, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ErrorLog", e.stackTraceToString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}