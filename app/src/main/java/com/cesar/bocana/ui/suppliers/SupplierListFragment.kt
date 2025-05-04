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

// Asegura que implementa SupplierActionListener
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
        // Pasar 'this' que ahora implementa la interfaz completa
        supplierAdapter = SupplierAdapter(this)
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
        suppliersListener = null // Liberar referencia
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            title = "Proveedores"
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            title = originalActivityTitle ?: getString(R.string.app_name)
            subtitle = null
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }
        originalActivityTitle = null // Limpiar referencia
    }


    private fun setupRecyclerView() {
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
            Log.w(TAG, "Supplier listener already attached.")
            return
        }
        showLoading(true)
        binding.textViewEmptySupplierList.visibility = View.GONE

        val query = firestore.collection("suppliers")
            .orderBy("name", Query.Direction.ASCENDING)

        suppliersListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                Log.w(TAG, "Snapshot received but binding is null or fragment not attached.")
                suppliersListener?.remove() // Intentar remover si ya no es válido
                suppliersListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error listening for suppliers", error)
                binding.textViewEmptySupplierList.text = "Error al cargar proveedores."
                binding.textViewEmptySupplierList.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val suppliers = snapshots.toObjects(Supplier::class.java)
                suppliers.firstOrNull()?.let { Log.d(TAG, "Primer proveedor leído: ${it.name}, isActive=${it.isActive}") }
                supplierAdapter.submitList(suppliers)
                binding.textViewEmptySupplierList.visibility = if (suppliers.isEmpty()) View.VISIBLE else View.GONE
                Log.d(TAG, "Proveedores actualizados: ${suppliers.size}")
            } else {
                Log.w(TAG, "Received null snapshot for suppliers query.")
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

    // Implementación del NUEVO método del listener
    override fun onSupplierStatusChanged(supplierId: String, newStatus: Boolean) {
        Log.d(TAG, "Listener: Actualizando estado para $supplierId a $newStatus")
        updateSupplierStatusFromList(supplierId, newStatus)
    }
    // ------------------------------------------

    // Función que realiza la actualización en Firestore
    private fun updateSupplierStatusFromList(supplierId: String, newStatus: Boolean) {
        if (supplierId.isEmpty()) {
            Log.e(TAG, "Supplier ID vacío, no se puede actualizar estado.")
            return
        }
        // Aquí NO mostramos ProgressBar para que el cambio sea rápido en la lista

        val supplierRef = firestore.collection("suppliers").document(supplierId)
        val statusUpdate = mapOf(
            "isActive" to newStatus,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        supplierRef.update(statusUpdate)
            .addOnSuccessListener {
                Log.i(TAG, "Estado del proveedor $supplierId actualizado a $newStatus desde la lista.")
                // La lista se actualizará sola por el listener 'observeSuppliers'
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al actualizar estado del proveedor $supplierId desde la lista", e)
                // Mostrar Toast de error si el contexto aún es válido
                context?.let {
                    Toast.makeText(it, "Error al actualizar estado", Toast.LENGTH_SHORT).show()
                }
                // Nota: La UI del chip podría quedar desincronizada momentáneamente hasta
                // que el listener principal (observeSuppliers) la corrija.
            }
    }


    // --- MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_ajustes)?.isVisible = false
        menu.findItem(R.id.action_devoluciones)?.isVisible = false
        menu.findItem(R.id.action_packaging)?.isVisible = false
        menu.findItem(R.id.action_suppliers)?.isVisible = false
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return false
    }

    companion object {
        private const val TAG = "SupplierListFragment"
    }
}