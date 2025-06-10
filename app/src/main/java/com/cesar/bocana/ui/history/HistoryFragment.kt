package com.cesar.bocana.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.databinding.FragmentHistoryBinding
import com.cesar.bocana.ui.adapters.StockMovementAdapter
import com.cesar.bocana.utils.FirestoreCollections
import com.cesar.bocana.utils.StockMovementFields
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var movementAdapter: StockMovementAdapter
    private val db = Firebase.firestore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        binding.progressBar.isVisible = true
        binding.emptyView.isVisible = false

        lifecycleScope.launch {
            try {
                val snapshot = db.collection(FirestoreCollections.STOCK_MOVEMENTS)
                    .orderBy(StockMovementFields.TIMESTAMP, Query.Direction.DESCENDING)
                    .limit(100) // Cargar los 100 movimientos más recientes
                    .get()
                    .await()

                val movements = snapshot.toObjects(StockMovement::class.java)
                movementAdapter.submitList(movements)

                binding.emptyView.isVisible = movements.isEmpty()

            } catch (e: Exception) {
                // Manejar error
            } finally {
                if (isAdded) {
                    binding.progressBar.isVisible = false
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}