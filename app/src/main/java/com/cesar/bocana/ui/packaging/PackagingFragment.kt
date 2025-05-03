package com.cesar.bocana.ui.packaging

import android.os.Bundle
import android.util.Log
import android.view.* // Importar Menu etc.
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R // Importar R
import com.cesar.bocana.data.model.PendingPackagingTask // Importar modelo
import com.cesar.bocana.databinding.FragmentPackagingBinding // Usa tu binding
import com.cesar.bocana.ui.adapters.PackagingActionListener // Importar interfaz
import com.cesar.bocana.ui.adapters.PackagingAdapter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

// Implementa la interfaz del adaptador y MenuProvider
class PackagingFragment : Fragment(), PackagingActionListener, MenuProvider {

    private var _binding: FragmentPackagingBinding? = null
    private val binding get() = _binding!!

    private lateinit var packagingAdapter: PackagingAdapter
    private lateinit var firestore: FirebaseFirestore
    private var packagingListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null
    private var originalActivitySubtitle: CharSequence? = null


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
        _binding = null
    }

    // --- Configuración UI ---
    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title; originalActivitySubtitle = subtitle
            title = "Pendiente de Empacar"; subtitle = null
            setDisplayHomeAsUpEnabled(true); setDisplayShowHomeEnabled(true)
        }
    }
    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            title = originalActivityTitle ?: getString(R.string.app_name); subtitle = originalActivitySubtitle
            setDisplayHomeAsUpEnabled(false); setDisplayShowHomeEnabled(false)
        }
        originalActivityTitle = null; originalActivitySubtitle = null
    }

    private fun setupRecyclerView() {
        packagingAdapter = PackagingAdapter(this) // 'this' es el listener
        binding.recyclerViewPackaging.apply {
            adapter = packagingAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // --- Lógica Firestore ---
    private fun observePackagingTasks() {
        showLoading(true)
        binding.textViewEmptyPackaging.visibility = View.GONE

        // Consultar la nueva colección, ordenar por fecha de recepción
        val query = firestore.collection("pendingPackaging")
            .orderBy("receivedAt", Query.Direction.ASCENDING) // Más antiguo primero

        packagingListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null) return@addSnapshotListener
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
                binding.textViewEmptyPackaging.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                Log.d(TAG, "Tareas pendientes de empaque: ${tasks.size}")

            }
        }
    }

    // --- Implementación de PackagingActionListener ---
    override fun onMarkPackagedClicked(task: PendingPackagingTask) {
        Log.d(TAG, "Marcar Empacado ID: ${task.id} - Producto: ${task.productName}")
        // Mostrar diálogo de confirmación
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar Empaque")
            .setMessage("¿Marcar '${task.productName}' (${task.quantityReceived} ${task.unit}) como empacado? Se quitará de esta lista.")
            .setPositiveButton("Sí, Empacado") { _, _ ->
                deletePackagingTask(task.id) // Llama a borrar la tarea
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Función para BORRAR la tarea pendiente de Firestore
    private fun deletePackagingTask(taskId: String) {
        if (taskId.isEmpty()) { Log.e(TAG, "ID de tarea vacío."); return }
        Log.d(TAG, "Borrando tarea de empaque: $taskId")


        firestore.collection("pendingPackaging").document(taskId)
            .delete()
            .addOnSuccessListener {
                Log.i(TAG, "Tarea $taskId marcada como empacada (borrada).")
                Toast.makeText(context, "Tarea marcada como empacada.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al borrar tarea $taskId", e)
                Toast.makeText(context, "Error al marcar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        //.addOnCompleteListener { /* Ocultar carga */ }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarPackaging.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    // --- Implementación de MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {
        // Ocultar items de navegación principal
        menu.findItem(R.id.action_ajustes)?.isVisible = false
        menu.findItem(R.id.action_devoluciones)?.isVisible = false
        menu.findItem(R.id.action_packaging)?.isVisible = false // Ocultar el propio item
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean { return false }

    // --- Companion Object ---
    companion object {
        private const val TAG = "PackagingFragment"
    }
}