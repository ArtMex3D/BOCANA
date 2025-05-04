package com.cesar.bocana.ui.packaging

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.databinding.FragmentPackagingBinding
import com.cesar.bocana.ui.adapters.PackagingActionListener
import com.cesar.bocana.ui.adapters.PackagingAdapter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class PackagingFragment : Fragment(), PackagingActionListener, MenuProvider {

    private var _binding: FragmentPackagingBinding? = null
    private val binding get() = _binding!!

    private lateinit var packagingAdapter: PackagingAdapter
    private lateinit var firestore: FirebaseFirestore
    private var packagingListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPackagingBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        setupRecyclerView()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        observePackagingTasks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreToolbar()
        packagingListener?.remove()
        packagingListener = null
        _binding = null
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            // MainActivity ya debería haber puesto "Pendiente Empacar"
            // title = "Pendiente de Empacar" // Opcional
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            // MainActivity restaurará título/subtítulo al volver a ProductListFragment
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }
        originalActivityTitle = null
    }

    private fun setupRecyclerView() {
        packagingAdapter = PackagingAdapter(this)
        binding.recyclerViewPackaging.apply {
            adapter = packagingAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observePackagingTasks() {
        if (packagingListener != null) {
            Log.w(TAG, "Packaging listener already attached.")
            return
        }
        showLoading(true)
        binding.textViewEmptyPackaging.visibility = View.GONE

        val query = firestore.collection("pendingPackaging")
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        packagingListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                Log.w(TAG, "Snapshot received but binding is null or fragment not attached.")
                packagingListener?.remove()
                packagingListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando tareas de empaque", error)
                binding.textViewEmptyPackaging.text = "Error al cargar tareas."
                binding.textViewEmptyPackaging.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val tasks = snapshots.toObjects(PendingPackagingTask::class.java)
                packagingAdapter.submitList(tasks)
                binding.textViewEmptyPackaging.text = "No hay items pendientes de empacar."
                binding.textViewEmptyPackaging.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                Log.d(TAG, "Tareas pendientes de empaque: ${tasks.size}")
            } else {
                Log.w(TAG, "Received null snapshot for packaging query.")
                binding.textViewEmptyPackaging.text = "No se encontraron tareas."
                binding.textViewEmptyPackaging.visibility = View.VISIBLE
            }
        }
    }

    override fun onMarkPackagedClicked(task: PendingPackagingTask) {
        Log.d(TAG, "Marcar Empacado ID: ${task.id} - Producto: ${task.productName}")
        // Aquí es donde, en Fase 3, cambiaremos la lógica para pedir pesos
        // Por ahora, solo muestra diálogo y borra la tarea (lógica original simplificada)
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar Empaque")
            .setMessage("¿Marcar '${task.productName}' (${String.format("%.2f", task.quantityReceived)} ${task.unit}) como empacado? Se quitará de esta lista.")
            .setPositiveButton("Sí, Empacado") { _, _ ->
                deletePackagingTask(task.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletePackagingTask(taskId: String) {
        if (taskId.isEmpty()) { Log.e(TAG, "ID de tarea vacío."); return }
        Log.d(TAG, "Borrando tarea de empaque: $taskId")
        showLoading(true) // Mostrar carga durante el borrado

        firestore.collection("pendingPackaging").document(taskId)
            .delete()
            .addOnSuccessListener {
                Log.i(TAG, "Tarea $taskId marcada como empacada (borrada).")
                if(context != null) { // Check context
                    Toast.makeText(context, "Tarea marcada como empacada.", Toast.LENGTH_SHORT).show()
                }
                // El listener actualizará la lista
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al borrar tarea $taskId", e)
                if(context != null) {
                    Toast.makeText(context, "Error al marcar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .addOnCompleteListener {
                if (_binding != null) { // Check binding
                    showLoading(false) // Ocultar carga al finalizar
                }
            }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarPackaging.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    // --- Implementación MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {
        Log.d(TAG, "onPrepareMenu (PackagingFragment)")
        // Las líneas que buscaban action_... SE HAN ELIMINADO
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean { return false }
    // --- Fin MenuProvider ---

    companion object {
        private const val TAG = "PackagingFragment"
    }
}