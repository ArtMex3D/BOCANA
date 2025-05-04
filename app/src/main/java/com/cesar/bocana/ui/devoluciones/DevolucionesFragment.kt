package com.cesar.bocana.ui.devoluciones

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
import com.cesar.bocana.data.model.DevolucionPendiente
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.databinding.FragmentDevolucionesBinding
import com.cesar.bocana.ui.adapters.DevolucionActionListener
import com.cesar.bocana.ui.adapters.DevolucionAdapter
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class DevolucionesFragment : Fragment(), DevolucionActionListener, MenuProvider {

    private var _binding: FragmentDevolucionesBinding? = null
    private val binding get() = _binding!!

    private lateinit var devolucionAdapter: DevolucionAdapter
    private lateinit var firestore: FirebaseFirestore
    private var devolucionesListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevolucionesBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        setupRecyclerView()
        Log.d(TAG, "onCreateView")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated - Configurando Toolbar y MenuProvider")
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        observeDevoluciones()
    }

    private fun setupToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            originalActivityTitle = title
            // MainActivity ya debería haber puesto "Devoluciones"
            // title = "Devoluciones Pendientes" // Opcional
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

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        restoreToolbar()
        devolucionesListener?.remove()
        devolucionesListener = null
        _binding = null
    }

    private fun setupRecyclerView() {
        devolucionAdapter = DevolucionAdapter(this)
        binding.recyclerViewDevoluciones.apply {
            adapter = devolucionAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observeDevoluciones() {
        if (devolucionesListener != null) {
            Log.w(TAG, "Listener de devoluciones ya activo.")
            return // Ya está escuchando
        }
        Log.d(TAG, "Iniciando escucha de devoluciones...")
        showLoading(true)
        binding.textViewEmptyDevoluciones.visibility = View.GONE

        val query = firestore.collection("pendingDevoluciones")
            .orderBy("status", Query.Direction.DESCENDING) // PENDIENTE primero
            .orderBy("registeredAt", Query.Direction.DESCENDING) // Más recientes primero

        devolucionesListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                Log.w(TAG, "Snapshot recibido pero binding es null o fragment no attached.")
                devolucionesListener?.remove()
                devolucionesListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando devoluciones", error)
                binding.textViewEmptyDevoluciones.text = if (error.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                    "Error: Índice Firestore requerido."
                } else {
                    "Error al cargar devoluciones."
                }
                binding.textViewEmptyDevoluciones.visibility = View.VISIBLE
                binding.recyclerViewDevoluciones.visibility = View.GONE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val devoluciones = snapshots.toObjects(DevolucionPendiente::class.java)
                devoluciones.firstOrNull()?.let { Log.d(TAG, "Primer item: ID=${it.id}, Status=${it.status}, Date=${it.registeredAt}")}
                devolucionAdapter.submitList(devoluciones)

                binding.textViewEmptyDevoluciones.text = "No hay devoluciones." // Ajustar mensaje si necesario
                binding.textViewEmptyDevoluciones.visibility = if (devoluciones.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewDevoluciones.visibility = if (devoluciones.isEmpty()) View.GONE else View.VISIBLE
                Log.d(TAG, "Devoluciones actualizadas: ${devoluciones.size}")
            } else {
                Log.w(TAG, "Received null snapshot for devoluciones query.")
                binding.textViewEmptyDevoluciones.text = "No se encontraron devoluciones."
                binding.textViewEmptyDevoluciones.visibility = View.VISIBLE
                binding.recyclerViewDevoluciones.visibility = View.GONE
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarDevoluciones.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onCompletarClicked(devolucion: DevolucionPendiente) {
        Log.d(TAG, "onCompletarClicked - ID: ${devolucion.id}, Status Actual: ${devolucion.status}")

        if (devolucion.status == DevolucionStatus.PENDIENTE) {
            Log.d(TAG, "Status es PENDIENTE. Mostrando diálogo para ID: ${devolucion.id}")
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar Completar")
                .setMessage("¿Marcar esta devolución como completada?\n(${String.format("%.2f", devolucion.quantity)} ${devolucion.unit} - ${devolucion.productName} a ${devolucion.provider})")
                .setPositiveButton("Sí, Completar") { _, _ ->
                    updateDevolucionStatusToCompleted(devolucion.id)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            Log.d(TAG, "Status NO es PENDIENTE (es ${devolucion.status}). Toast para ID: ${devolucion.id}")
            Toast.makeText(context, "Esta devolución ya está completada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDevolucionStatusToCompleted(devolucionId: String) {
        if (devolucionId.isEmpty()) { Log.e(TAG, "ID vacío"); Toast.makeText(context, "Error ID", Toast.LENGTH_SHORT).show(); return }
        Log.d(TAG, "Actualizando a COMPLETADO: $devolucionId")

        val devolucionRef = firestore.collection("pendingDevoluciones").document(devolucionId)

        val updates = mapOf(
            "status" to DevolucionStatus.COMPLETADO.name,
            "completedAt" to FieldValue.serverTimestamp()
        )

        devolucionRef.update(updates)
            .addOnSuccessListener { Log.i(TAG, "$devolucionId completada."); Toast.makeText(context, "Devolución completada.", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Log.e(TAG, "Error completando $devolucionId", e); Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // --- Implementación MenuProvider ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) { }

    override fun onPrepareMenu(menu: Menu) {
        // Ocultar el único item del menú superior (Logout) si se desea
        Log.d(TAG, "onPrepareMenu (DevolucionesFragment)")
        // Las líneas que buscaban action_ajustes, action_devoluciones SE HAN ELIMINADO
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // MainActivity maneja R.id.home (flecha atrás) y R.id.action_logout
        return false
    }
    // --- Fin MenuProvider ---

    companion object { private const val TAG = "DevolucionesFragment" }
}