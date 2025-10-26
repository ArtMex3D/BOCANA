package com.cesar.bocana.ui.masopciones

import android.os.Bundle
import android.util.Log // Asegúrate que Log está importado
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.databinding.FragmentMoreOptionsBinding
import com.cesar.bocana.ui.ajustes.AjustesFragment
import com.cesar.bocana.ui.archived.ArchivedProductsFragment
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.history.AdvancedHistoryFragment
import com.cesar.bocana.ui.history.HistoryFragment
import com.cesar.bocana.ui.migration.CategoryMigrationFragment
import com.cesar.bocana.ui.migration.LotMigrationFragment
import com.cesar.bocana.ui.report.ReportConfigFragment
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.cesar.bocana.ui.traspasos.config.ConfiguracionTraspasoFragment
import com.cesar.bocana.utils.ConnectivityObserver
import com.cesar.bocana.utils.NetworkStatus
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MoreOptionsFragment : Fragment() {


    private var _binding: FragmentMoreOptionsBinding? = null
    private val binding get() = _binding!!
    private val firestore = Firebase.firestore
    private lateinit var repository: InventoryRepository // Declarar la variable
    private lateinit var connectivityObserver: ConnectivityObserver
    private val TAG_CLEANUP = "LotCleanup" // Tag específico para logs de limpieza


    override fun onCreateView( inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreOptionsBinding.inflate(inflater, container, false)
        // Inicializar el repositorio
        val database = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(database, firestore)
        connectivityObserver = ConnectivityObserver(requireContext().applicationContext)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeNetworkStatus() // Start observing network status
    }

    private fun observeNetworkStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            connectivityObserver.observe().collect { isOnline ->
                updateButtonStates(isOnline)
            }
        }
    }

    private fun updateButtonStates(isOnline: Boolean) {
        // Buttons that require writing to Firestore
        binding.buttonNavToAjustes.isEnabled = isOnline
        binding.buttonNavToArchivedProducts.isEnabled = isOnline // Reactivating is a write operation
        binding.buttonNavToDevoluciones.isEnabled = isOnline // Completing is a write operation
        binding.buttonNavToConfigTraspasos.isEnabled = isOnline
        binding.buttonNavToProveedores.isEnabled = isOnline
        binding.buttonNavToLotMigration.isEnabled = isOnline
        binding.buttonNavToCategoryMigration.isEnabled = isOnline
        binding.buttonForceCleanupReservas.isEnabled = isOnline

        // Buttons that primarily read local data or are for navigation
        binding.buttonAdvancedHistory.isEnabled = true
        binding.buttonHistory.isEnabled = true
        binding.buttonNavToReportGenerator.isEnabled = true // PDF generation is local
    }


    private fun setupListeners() {
        // Botón para el historial simple (el original)
        binding.buttonHistory.setOnClickListener {
            val historyFragment = HistoryFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, historyFragment)
                .addToBackStack(null)
                .commit()
        }

        // Botón para la nueva búsqueda avanzada
        binding.buttonAdvancedHistory.setOnClickListener {
            val advancedHistoryFragment = AdvancedHistoryFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, advancedHistoryFragment)
                .addToBackStack(null)
                .commit()
        }

        // --- Resto de los botones sin cambios ---
        binding.buttonNavToAjustes.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, AjustesFragment())
                .addToBackStack("AjustesFragment")
                .commit()
        }

        binding.buttonNavToArchivedProducts.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, ArchivedProductsFragment())
                .addToBackStack("ArchivedProductsFragment")
                .commit()
        }

        binding.buttonNavToReportGenerator.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, com.cesar.bocana.ui.report.ReportConfigFragment())
                .addToBackStack("ReportConfigFragment")
                .commit()
        }

        // NUEVO: Listener para el botón de devoluciones
        binding.buttonNavToDevoluciones.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, DevolucionesFragment())
                .addToBackStack("DevolucionesFragment")
                .commit()
        }
        binding.buttonNavToProveedores.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, SupplierListFragment())
                .addToBackStack("SupplierListFragment")
                .commit()
        }

        binding.buttonNavToConfigTraspasos.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, ConfiguracionTraspasoFragment())
                .addToBackStack("ConfiguracionTraspasoFragment")
                .commit()
        }



        binding.buttonNavToLotMigration.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, LotMigrationFragment())
                .addToBackStack("LotMigrationFragment")
                .commit()
        }

        binding.buttonNavToCategoryMigration.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, CategoryMigrationFragment())
                .addToBackStack("CategoryMigrationFragment")
                .commit()
        }

        binding.buttonForceCleanupReservas.setOnClickListener {
            showForceCleanupConfirmationDialog()
        }

    }

    private fun showForceCleanupConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Limpieza Completa de Lotes")
            .setMessage("Esta acción realizará dos tareas:\n\n1. Buscará lotes reservados por planes y los liberará (estadoTraspaso = null).\n2. Buscará lotes antiguos sin el campo 'estadoTraspaso' y lo añadirá (estadoTraspaso = null).\n\n¿Deseas continuar?")
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton("Sí, Limpiar Todo") { _, _ ->
                // Llamamos a la función modificada
                runMasterLotCleanupAndAddMissingField()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    private fun runMasterLotCleanupAndAddMissingField() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Limpiando Lotes...")
            .setMessage("Realizando limpieza completa...")
            .setCancelable(false)
            .create()

        progressDialog.show()
        binding.buttonForceCleanupReservas.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var lotesLiberados = 0
            var lotesCreadosCampo = 0
            val batchSize = 400 // Firestore recomienda < 500 operaciones por batch
            var batchesCommitted = 0

            try {
                // --- Parte 1: Liberar lotes reservados ---
                Log.d(TAG_CLEANUP, "Iniciando Parte 1: Liberar lotes reservados...")
                var lastVisibleReserved: com.google.firebase.firestore.DocumentSnapshot? = null
                var batchLiberar = firestore.batch()
                var opsLiberar = 0

                do {
                    val queryReserved = if (lastVisibleReserved == null) {
                        firestore.collection("inventoryLots")
                            .whereNotEqualTo("estadoTraspaso", null) // Busca donde el campo existe y NO es null
                            .limit(batchSize.toLong())
                    } else {
                        firestore.collection("inventoryLots")
                            .whereNotEqualTo("estadoTraspaso", null)
                            .startAfter(lastVisibleReserved)
                            .limit(batchSize.toLong())
                    }

                    val reservedSnapshot = queryReserved.get().await()
                    val reservedDocs = reservedSnapshot.documents

                    if (reservedDocs.isEmpty()) break

                    Log.d(TAG_CLEANUP, "Parte 1: Procesando ${reservedDocs.size} lotes reservados...")
                    for (doc in reservedDocs) {
                        Log.v(TAG_CLEANUP, "Parte 1: Liberando lote ${doc.id}")
                        batchLiberar.update(doc.reference, "estadoTraspaso", null) // CAMBIO: Usar null
                        lotesLiberados++
                        opsLiberar++
                        if (opsLiberar >= batchSize) {
                            Log.d(TAG_CLEANUP, "Parte 1: Ejecutando batch de liberación ${batchesCommitted}...")
                            batchLiberar.commit().await()
                            batchesCommitted++
                            batchLiberar = firestore.batch()
                            opsLiberar = 0
                        }
                    }
                    lastVisibleReserved = reservedDocs.lastOrNull()
                } while (lastVisibleReserved != null)

                if (opsLiberar > 0) {
                    Log.d(TAG_CLEANUP, "Parte 1: Ejecutando batch final de liberación ${batchesCommitted}...")
                    batchLiberar.commit().await()
                    batchesCommitted++
                }
                Log.i(TAG_CLEANUP, "Parte 1 completada. Lotes liberados: $lotesLiberados.")

                // --- Parte 2: Añadir campo 'estadoTraspaso: null' donde falte ---
                Log.d(TAG_CLEANUP, "Iniciando Parte 2: Añadir campo 'estadoTraspaso' faltante...")
                var lastVisibleMissing: com.google.firebase.firestore.DocumentSnapshot? = null
                var batchAgregar = firestore.batch()
                var opsAgregar = 0
                var documentsProcessed = 0 // Contador solo para esta parte

                do {
                    // Nota: Firestore no tiene un operador "field does not exist".
                    // Esta paginación revisará TODOS los lotes, pero solo actuará en los que falte el campo.
                    // Es menos eficiente que la Parte 1, pero necesario.
                    val queryMissing = if (lastVisibleMissing == null) {
                        firestore.collection("inventoryLots").limit(batchSize.toLong())
                    } else {
                        firestore.collection("inventoryLots").startAfter(lastVisibleMissing).limit(batchSize.toLong())
                    }

                    val missingSnapshot = queryMissing.get().await()
                    val missingDocs = missingSnapshot.documents

                    if (missingDocs.isEmpty()) break

                    Log.d(TAG_CLEANUP, "Parte 2: Revisando ${missingDocs.size} documentos...")
                    documentsProcessed += missingDocs.size

                    for (doc in missingDocs) {
                        // Verificar si el campo NO existe
                        if (!doc.contains("estadoTraspaso")) {
                            Log.v(TAG_CLEANUP, "Parte 2: Añadiendo estadoTraspaso: null al lote ${doc.id}")
                            batchAgregar.update(doc.reference, "estadoTraspaso", null)
                            lotesCreadosCampo++
                            opsAgregar++
                            if (opsAgregar >= batchSize) {
                                Log.d(TAG_CLEANUP, "Parte 2: Ejecutando batch de adición ${batchesCommitted}...")
                                batchAgregar.commit().await()
                                batchesCommitted++
                                batchAgregar = firestore.batch()
                                opsAgregar = 0
                            }
                        }
                    }
                    lastVisibleMissing = missingDocs.lastOrNull()
                } while (lastVisibleMissing != null)

                if (opsAgregar > 0) {
                    Log.d(TAG_CLEANUP, "Parte 2: Ejecutando batch final de adición ${batchesCommitted}...")
                    batchAgregar.commit().await()
                    batchesCommitted++
                }
                Log.i(TAG_CLEANUP, "Parte 2 completada. Lotes con campo añadido: $lotesCreadosCampo. Documentos revisados: $documentsProcessed")

                // --- Mensaje Final ---
                withContext(Dispatchers.Main) {
                    val message = "Limpieza completada:\n- Lotes liberados: $lotesLiberados\n- Lotes con campo añadido: $lotesCreadosCampo"
                    AlertDialog.Builder(requireContext())
                        .setTitle("Éxito")
                        .setMessage(message)
                        .setPositiveButton("Aceptar", null)
                        .show()
                }

            } catch (e: Exception) {
                Log.e(TAG_CLEANUP, "Error durante la limpieza completa de lotes", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error durante la limpieza: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (_binding != null) { // Verificar si el binding todavía existe
                        binding.buttonForceCleanupReservas.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
