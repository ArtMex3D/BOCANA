package com.cesar.bocana.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.databinding.FragmentHistoryBinding
import com.cesar.bocana.ui.adapters.StockMovementAdapter
import com.cesar.bocana.utils.FirestoreCollections
import com.cesar.bocana.utils.StockMovementFields
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var movementAdapter: StockMovementAdapter
    private val db = Firebase.firestore
    private val localDb by lazy { AppDatabase.getDatabase(requireContext()).stockMovementDao() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Historial de Movimientos"
        setupRecyclerView()
        loadMovements()
    }

    private fun setupRecyclerView() {
        movementAdapter = StockMovementAdapter(requireContext())
        binding.historyRecyclerView.adapter = movementAdapter
    }

    private fun loadMovements() {
        lifecycleScope.launch {
            binding.progressBar.isVisible = true

            // 1. Cargar datos de la caché local primero
            try {
                val cachedMovements = localDb.getAllMovements()
                if (isAdded) {
                    movementAdapter.submitList(cachedMovements)
                    binding.emptyView.isVisible = cachedMovements.isEmpty()
                    binding.progressBar.isVisible = cachedMovements.isEmpty()
                }
            } catch (e: Exception) {
                if (isAdded) showErrorDialog("Error al leer la caché local", e)
            }


            // 2. Sincronizar con Firestore en segundo plano
            try {
                val latestTimestampInMillis = localDb.getLatestTimestamp()
                var query = db.collection(FirestoreCollections.STOCK_MOVEMENTS)
                    .orderBy(StockMovementFields.TIMESTAMP, Query.Direction.DESCENDING)

                if (latestTimestampInMillis != null) {
                    query = query.whereGreaterThan(StockMovementFields.TIMESTAMP, Date(latestTimestampInMillis))
                } else {
                    query = query.limit(100)
                }

                val snapshot = query.get().await()
                val newMovements = snapshot.toObjects(StockMovement::class.java)

                if (newMovements.isNotEmpty()) {
                    localDb.insertAll(newMovements)
                    val allMovements = localDb.getAllMovements()
                    if (isAdded) {
                        movementAdapter.submitList(allMovements)
                        binding.emptyView.isVisible = allMovements.isEmpty()
                    }
                }
            } catch (e: Exception) {
                if (isAdded) showErrorDialog("Error al sincronizar con Firestore", e)
            } finally {
                if (isAdded) {
                    binding.progressBar.isVisible = false
                }
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