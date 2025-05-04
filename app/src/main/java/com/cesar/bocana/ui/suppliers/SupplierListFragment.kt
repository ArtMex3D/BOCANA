package com.cesar.bocana.ui.suppliers

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Supplier
import com.cesar.bocana.databinding.FragmentSupplierListBinding
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class SupplierListFragment : Fragment(), SupplierActionListener, MenuProvider {

    private var _binding: FragmentSupplierListBinding? = null
    private val binding get() = _binding!!

    private lateinit var supplierAdapter: SupplierAdapter
    private lateinit var firestore: FirebaseFirestore
    private var suppliersListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupplierListBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        supplierAdapter = SupplierAdapter(this) // Inicializar aquí
        setupRecyclerView()
        setupFab()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        observeSuppliers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreToolbar()
        suppliersListener?.remove()
        suppliersListener = null
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            title = "Proveedores" // Asegurar título correcto
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
            // MainActivity se encargará de restaurar su título/subtítulo
        }
        originalActivityTitle = null
    }


    private fun setupRecyclerView() {
        // Adapter ya inicializado en onCreateView
        binding.recyclerViewSuppliers.apply {
            adapter = supplierAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupFab() {
        binding.fabAddSupplier.setOnClickListener {
            navigateToEditSupplier(null)
        }
    }

    private fun navigateToEditSupplier(supplierId: String?) {
        val fragment = AddEditSupplierFragment.newInstance(supplierId)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun observeSuppliers() {
        if (suppliersListener != null) {
            Log.w(TAG, "Supplier listener ya activo.")
            return
        }
        showLoading(true)
        binding.textViewEmptySupplierList.visibility = View.GONE

        val query = firestore.collection("suppliers")
            .orderBy("name", Query.Direction.ASCENDING)

        suppliersListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                Log.w(TAG, "Snapshot suppliers pero binding/fragment no válido")
                suppliersListener?.remove(); suppliersListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando proveedores", error)
                binding.textViewEmptySupplierList.text = "Error al cargar proveedores."
                binding.textViewEmptySupplierList.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val suppliers = snapshots.toObjects(Supplier::class.java)
                Log.d(TAG, "Proveedores actualizados: ${suppliers.size}. Primer item isActive=${suppliers.firstOrNull()?.isActive}")
                supplierAdapter.submitList(suppliers)
                binding.textViewEmptySupplierList.text = "No hay proveedores registrados."
                binding.textViewEmptySupplierList.visibility = if (suppliers.isEmpty()) View.VISIBLE else View.GONE
            } else {
                Log.w(TAG, "Snapshot suppliers es null")
                binding.textViewEmptySupplierList.text = "No se encontraron proveedores."
                binding.textViewEmptySupplierList.visibility = View.VISIBLE
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarSupplierList.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    // --- Implementación SupplierActionListener ---
    override fun onSupplierClicked(supplier: Supplier) {
        navigateToEditSupplier(supplier.id)
    }

    override fun onSupplierStatusChanged(supplierId: String, newStatus: Boolean) {
        Log.d(TAG, "Listener: Actualizando estado para $supplierId a $newStatus")
        updateSupplierStatusFromList(supplierId, newStatus)
    }
    // ------------------------------------------

    private fun updateSupplierStatusFromList(supplierId: String, newStatus: Boolean) {
        if (supplierId.isEmpty()) {
            Log.e(TAG, "Supplier ID vacío.")
            return
        }
        // Considerar deshabilitar el chip mientras se guarda para evitar doble click rápido
        // val position = supplierAdapter.currentList.indexOfFirst { it.id == supplierId }
        // val viewHolder = binding.recyclerViewSuppliers.findViewHolderForAdapterPosition(position) as? SupplierAdapter.SupplierViewHolder
        // viewHolder?.binding?.chipSupplierStatus?.isEnabled = false // Ejemplo

        val supplierRef = firestore.collection("suppliers").document(supplierId)
        val statusUpdate = mapOf(
            "isActive" to newStatus,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        supplierRef.update(statusUpdate)
            .addOnSuccessListener {
                Log.i(TAG, "Estado del proveedor $supplierId actualizado a $newStatus desde lista.")
                // No es necesario Toast, el listener 'observeSuppliers' actualizará la UI.
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al actualizar estado del proveedor $supplierId desde lista", e)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Error al actualizar estado", Toast.LENGTH_SHORT).show()
                }
                // El listener 'observeSuppliers' eventualmente corregirá la UI al estado de Firestore.
            }
        // .addOnCompleteListener {
        //    viewHolder?.binding?.chipSupplierStatus?.isEnabled = true // Rehabilitar chip
        // }
    }

    // --- MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onPrepareMenu(menu: Menu) {
        Log.d(TAG, "onPrepareMenu (SupplierListFragment)")
        // No necesitamos ocultar nada del menú superior (que solo tiene Logout)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }
    // --- Fin MenuProvider ---

    companion object {
        private const val TAG = "SupplierListFragment"
    }
}