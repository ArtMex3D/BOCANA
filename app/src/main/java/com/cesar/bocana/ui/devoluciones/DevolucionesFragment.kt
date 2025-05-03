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
import com.cesar.bocana.data.model.DevolucionStatus // <--- IMPORT NECESARIO
import com.cesar.bocana.databinding.FragmentDevolucionesBinding
import com.cesar.bocana.ui.adapters.DevolucionActionListener
import com.cesar.bocana.ui.adapters.DevolucionAdapter
import com.google.firebase.firestore.FieldValue // <--- IMPORT NECESARIO
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
    private var originalActivitySubtitle: CharSequence? = null

    // --- Ciclo de Vida y Configuración ---
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
        // ... (código sin cambios) ...
        val activity = requireActivity() as? AppCompatActivity; activity?.supportActionBar?.let { ab -> originalActivityTitle = ab.title; originalActivitySubtitle = ab.subtitle; ab.title = "Devoluciones Pendientes"; ab.subtitle = null; ab.setDisplayHomeAsUpEnabled(true); ab.setDisplayShowHomeEnabled(true); Log.d(TAG,"Toolbar OK") }
    }

    private fun restoreToolbar() {
        // ... (código sin cambios) ...
        val activity = requireActivity() as? AppCompatActivity; activity?.supportActionBar?.let { ab -> ab.title = originalActivityTitle ?: getString(R.string.app_name); ab.subtitle = originalActivitySubtitle; ab.setDisplayHomeAsUpEnabled(false); ab.setDisplayShowHomeEnabled(false); Log.d(TAG,"Toolbar Restaurada") }; originalActivityTitle = null; originalActivitySubtitle = null
    }

    override fun onDestroyView() {
        // ... (código sin cambios) ...
        super.onDestroyView(); Log.d(TAG, "onDestroyView"); restoreToolbar(); devolucionesListener?.remove(); _binding = null
    }

    private fun setupRecyclerView() {
        // ... (código sin cambios) ...
        devolucionAdapter = DevolucionAdapter(this); binding.recyclerViewDevoluciones.apply { adapter = devolucionAdapter; layoutManager = LinearLayoutManager(requireContext()) }
    }

    private fun observeDevoluciones() {
        if (devolucionesListener != null) { // Evitar listeners duplicados
            Log.w(TAG, "Listener de devoluciones ya activo.")
            showLoading(false)
            return
        }
        Log.d(TAG, "Iniciando escucha de devoluciones con orden CORRECTO...")
        showLoading(true); binding.textViewEmptyDevoluciones.visibility = View.GONE

        // --- Consulta CORREGIDA ---
        val query = firestore.collection("pendingDevoluciones")
            .orderBy("status", Query.Direction.DESCENDING) // <-- DESCENDING para PENDIENTE primero
            .orderBy("registeredAt", Query.Direction.DESCENDING) // Más recientes primero dentro de cada estado

        devolucionesListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null) { Log.w(TAG, "Snapshot recibido pero binding es null."); return@addSnapshotListener }
            showLoading(false)

            if (error != null) {
                // Manejo del error del índice debería estar resuelto, pero dejamos el log por si acaso
                Log.e(TAG, "Error escuchando devoluciones", error)
                if (error.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                    binding.textViewEmptyDevoluciones.text = "Error: Índice de Firestore requerido. Contacta al desarrollador."
                } else {
                    binding.textViewEmptyDevoluciones.text = "Error al cargar devoluciones."
                }
                binding.textViewEmptyDevoluciones.visibility = View.VISIBLE
                binding.recyclerViewDevoluciones.visibility = View.GONE // Ocultar lista si hay error
                return@addSnapshotListener
            }

            if (snapshots != null) {
                val devoluciones = snapshots.toObjects(DevolucionPendiente::class.java)
                // Log para verificar el orden recibido de Firestore
                if (devoluciones.isNotEmpty()) {
                    Log.d(TAG, "Firestore data order check (después de corregir query):")
                    devoluciones.take(5).forEachIndexed { index, dev ->
                        Log.d(TAG, "Item $index: ID=${dev.id}, Status=${dev.status}, Date=${dev.registeredAt}")
                    }
                }
                // Enviar lista al adaptador
                devolucionAdapter.submitList(devoluciones)

                // Mostrar/ocultar mensaje de lista vacía
                binding.textViewEmptyDevoluciones.text = "No hay devoluciones pendientes." // Mensaje por defecto
                binding.textViewEmptyDevoluciones.visibility = if (devoluciones.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewDevoluciones.visibility = if (devoluciones.isEmpty()) View.GONE else View.VISIBLE
                Log.d(TAG, "Devoluciones actualizadas: ${devoluciones.size}")
            }
        }
    }
    private fun showLoading(isLoading: Boolean) {
        // ... (código sin cambios) ...
        if (_binding != null) { binding.progressBarDevoluciones.visibility = if (isLoading) View.VISIBLE else View.GONE }
    }

    // --- Implementación de DevolucionActionListener (CORREGIDA) ---
    override fun onCompletarClicked(devolucion: DevolucionPendiente) {
        // Log para ver el status actual ANTES del if
        Log.d(TAG, "onCompletarClicked - ID: ${devolucion.id}, Status Actual: ${devolucion.status}")

        // ---> Comprobación CORREGIDA <---
        if (devolucion.status == DevolucionStatus.PENDIENTE) {
            Log.d(TAG, "Status es PENDIENTE. Mostrando diálogo para ID: ${devolucion.id}")
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar Completar")
                .setMessage("¿Marcar esta devolución como completada?\n(${devolucion.quantity} - ${devolucion.productName} a ${devolucion.provider})")
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

    // Función para ACTUALIZAR estado (CORREGIDA con imports correctos)
    private fun updateDevolucionStatusToCompleted(devolucionId: String) {
        if (devolucionId.isEmpty()) { Log.e(TAG, "ID vacío"); Toast.makeText(context, "Error ID", Toast.LENGTH_SHORT).show(); return }
        Log.d(TAG, "Actualizando a COMPLETADO: $devolucionId")

        val devolucionRef = firestore.collection("pendingDevoluciones").document(devolucionId)

        // Mapa con los campos a actualizar
        val updates = mapOf(
            // ---> Usa DevolucionStatus aquí <---
            "status" to DevolucionStatus.COMPLETADO.name, // Guarda String "COMPLETADO"
            // ---> Usa FieldValue aquí <---
            "completedAt" to FieldValue.serverTimestamp() // Importado correctamente
        )

        devolucionRef.update(updates)
            .addOnSuccessListener { Log.i(TAG, "$devolucionId completada."); Toast.makeText(context, "Devolución completada.", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Log.e(TAG, "Error completando $devolucionId", e); Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // --- Implementación de MenuProvider (sin cambios) ---
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) { /* Vacío */ }
    override fun onPrepareMenu(menu: Menu) {
        // ... (código para ocultar items como estaba) ...
        Log.d(TAG, "onPrepareMenu: Ocultando items nav")
        menu.findItem(R.id.action_ajustes)?.isVisible = false
        menu.findItem(R.id.action_devoluciones)?.isVisible = false
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean { return false } // No maneja items aquí

    // --- Companion Object ---
    companion object { private const val TAG = "DevolucionesFragment" }
}