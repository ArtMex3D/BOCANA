package com.cesar.bocana.ui.archived

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentArchivedProductsBinding
import com.cesar.bocana.ui.adapters.ArchivedProductActionListener
import com.cesar.bocana.ui.adapters.ArchivedProductAdapter
import com.cesar.bocana.ui.products.AddEditProductFragment
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ArchivedProductsFragment : Fragment(), ArchivedProductActionListener {

    private var _binding: FragmentArchivedProductsBinding? = null
    private val binding get() = _binding!!

    private lateinit var archivedAdapter: ArchivedProductAdapter
    private lateinit var firestore: FirebaseFirestore
    private var productsListener: ListenerRegistration? = null
    private val auth = Firebase.auth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchivedProductsBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        setupRecyclerView()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Productos Archivados"
        observeArchivedProducts()
    }

    private fun setupRecyclerView() {
        archivedAdapter = ArchivedProductAdapter(this)
        binding.recyclerViewArchivedProducts.apply {
            adapter = archivedAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observeArchivedProducts() {
        showLoading(true)
        binding.textViewEmptyArchived.visibility = View.GONE

        val query = firestore.collection("products")
            .whereEqualTo("isActive", false) // Solo productos inactivos
            .orderBy("name", Query.Direction.ASCENDING)

        productsListener?.remove() // Remover listener anterior si existe
        productsListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                productsListener?.remove()
                productsListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando productos archivados", error)
                binding.textViewEmptyArchived.text = "Error al cargar archivados."
                binding.textViewEmptyArchived.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val products = snapshots.toObjects(Product::class.java)
                archivedAdapter.submitList(products)
                binding.textViewEmptyArchived.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onReactivateProductClicked(product: Product) {
        AlertDialog.Builder(requireContext())
            .setTitle("Reactivar Producto")
            .setMessage("¿Deseas reactivar el producto '${product.name}'? Volverá a aparecer en la lista principal de stocks.")
            .setPositiveButton("Sí, Reactivar") { _, _ ->
                updateProductStatus(product.id, true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onArchivedProductClicked(product: Product) {
        // Navegar a AddEditProductFragment para ver detalles o reactivar desde allí
        val editFragment = AddEditProductFragment.newInstance(product.id)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, editFragment)
            .addToBackStack("EditArchivedProduct")
            .commit()
    }

    private fun updateProductStatus(productId: String, newStatus: Boolean) {
        showLoading(true)
        val currentUser = auth.currentUser
        val currentUserName = currentUser?.displayName ?: currentUser?.email ?: "Desconocido"

        firestore.collection("products").document(productId)
            .update(mapOf(
                "isActive" to newStatus,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))
            .addOnSuccessListener {
                if(_binding == null || !isAdded) return@addOnSuccessListener
                showLoading(false)
                Toast.makeText(context, "Producto ${if (newStatus) "reactivado" else "archivado"}.", Toast.LENGTH_SHORT).show()
                // La lista se actualizará automáticamente por el listener
            }
            .addOnFailureListener { e ->
                if(_binding == null || !isAdded) return@addOnFailureListener
                showLoading(false)
                Toast.makeText(context, "Error al actualizar estado: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }


    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarArchived.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        productsListener?.remove()
        productsListener = null
        _binding = null
    }

    companion object {
        private const val TAG = "ArchivedProductsFrag"
    }
}
