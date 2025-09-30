package com.cesar.bocana.ui.masopciones

import android.os.Bundle
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
import com.cesar.bocana.ui.migration.LotMigrationFragment
import com.cesar.bocana.ui.report.ReportConfigFragment
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.cesar.bocana.ui.traspasos.config.ConfiguracionTraspasoFragment
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext



class MoreOptionsFragment : Fragment() {

    private var _binding: FragmentMoreOptionsBinding? = null
    private val binding get() = _binding!!
    private val firestore = Firebase.firestore
    private lateinit var repository: InventoryRepository // Declarar la variable

    override fun onCreateView( inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreOptionsBinding.inflate(inflater, container, false)
        // Inicializar el repositorio
        val database = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(database, firestore)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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


        //boton para forzar sincronizacion, descomentar para activar
        //binding.buttonForceSync.setOnClickListener {showForceSyncConfirmationDialog() }

        //boton para forzar mantenimiento, descomentar para activar
        //binding.buttonMigrateData.setOnClickListener { showMigrationConfirmationDialog() }
    }


       /* private fun showForceSyncConfirmationDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar Sincronización")
                .setMessage("Esto borrará los datos locales y los volverá a descargar desde la nube. Es útil para corregir productos que no aparecen en la web.\n\n¿Deseas continuar?")
                .setPositiveButton("Sí, Sincronizar") { _, _ ->
                    runForceSync()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        private fun runForceSync() { val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle("Sincronizando...")
                .setMessage("Borrando caché local y descargando datos frescos...")
                .setCancelable(false)
                .create()
            progressDialog.show()
            binding.buttonForceSync.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    repository.forceFullResync()
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(context, "¡Sincronización completada!", Toast.LENGTH_LONG).show()
                        binding.buttonForceSync.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(context, "Error en la sincronización: ${e.message}", Toast.LENGTH_LONG).show()
                        binding.buttonForceSync.isEnabled = true
                    }
                }
            }
        }
        private fun showMigrationConfirmationDialog() {

            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar Mantenimiento")
                .setMessage("Esto reparará y actualizará todos los productos para que coincidan con la estructura de datos actual. Los campos desconocidos serán eliminados.\n\n¿Deseas continuar?")
                .setPositiveButton("Sí, Actualizar Ahora") { _, _ ->
                    runMigrationScript()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    private fun runMigrationScript() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Reparando y Actualizando...")
            .setMessage("Este proceso puede tardar unos minutos. Por favor, espera.")
            .setCancelable(false)
            .create()

        progressDialog.show()
        binding.buttonMigrateData.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // --- FASE 1: Limpiar y Estandarizar la Colección 'products' ---
                val validProductFields = setOf(
                    "id", "name", "unit", "minStock", "stockIdealC04", "stockMatriz",
                    "stockCongelador04", "totalStock", "createdAt", "updatedAt",
                    "lastUpdatedByName", "isActive", "requiresPackaging", "ordenTraspaso",
                    "modoManualPDF", "espacioExtraPDF", "labelConfig"
                )
                val productsCollection = firestore.collection("products")
                val productsSnapshot = productsCollection.get().await()
                var batch = firestore.batch()
                var productsProcessed = 0
                var batchCounter = 0

                for (document in productsSnapshot.documents) {
                    val productRef = document.reference
                    val data = document.data ?: continue
                    val updates = mutableMapOf<String, Any?>()

                    // Limpieza segura: elimina solo los campos que ya no están en el modelo
                    data.keys.forEach { key ->
                        if (key !in validProductFields) {
                            updates[key] = FieldValue.delete()
                        }
                    }

                    // Adición segura: añade campos clave si no existen
                    if (!data.containsKey("requiresPackaging")) {
                        updates["requiresPackaging"] = false
                    }
                    if (!data.containsKey("stockIdealC04")) {
                        updates["stockIdealC04"] = 0.0
                    }

                    if (updates.isNotEmpty()) {
                        batch.update(productRef, updates)
                        productsProcessed++
                        batchCounter++
                    }

                    if (batchCounter >= 400) {
                        batch.commit().await()
                        batch = firestore.batch()
                        batchCounter = 0
                    }
                }
                if (batchCounter > 0) {
                    batch.commit().await()
                }

                // --- FASE 2: Adaptar Lotes Existentes ---
                batch = firestore.batch() // Reiniciar batch
                batchCounter = 0
                val lotsCollection = firestore.collection("inventoryLots")
                val lotsSnapshot = lotsCollection.get().await()
                var lotsProcessed = 0

                for (document in lotsSnapshot.documents) {
                    val lotRef = document.reference
                    val data = document.data ?: continue
                    val lotUpdates = mutableMapOf<String, Any?>()

                    // Añade los nuevos campos como null si no existen
                    if (!data.containsKey("unidadDeEmpaque")) lotUpdates["unidadDeEmpaque"] = null
                    if (!data.containsKey("pesoPorUnidad")) lotUpdates["pesoPorUnidad"] = null
                    if (!data.containsKey("cantidadInicialUnidades")) lotUpdates["cantidadInicialUnidades"] = null

                    if (lotUpdates.isNotEmpty()) {
                        batch.update(lotRef, lotUpdates)
                        lotsProcessed++
                        batchCounter++
                    }
                    if (batchCounter >= 400) {
                        batch.commit().await()
                        batch = firestore.batch()
                        batchCounter = 0
                    }
                }
                if (batchCounter > 0) {
                    batch.commit().await()
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    val message = "Mantenimiento completado:\n- $productsProcessed productos verificados/actualizados.\n- $lotsProcessed lotes preparados para la nueva versión."
                    AlertDialog.Builder(requireContext())
                        .setTitle("¡Éxito!")
                        .setMessage(message)
                        .setPositiveButton("Aceptar", null)
                        .show()
                    binding.buttonMigrateData.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Error crítico durante el mantenimiento: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.buttonMigrateData.isEnabled = true
                }
            }
        }
    }
        private fun showFullErrorLog(errors: List<String>) {
            val errorText = errors.joinToString("\n\n")

            AlertDialog.Builder(requireContext())
                .setTitle("Log de Correcciones y Errores")
                .setMessage(errorText)
                .setPositiveButton("Cerrar", null)
                .show()
        }
    borrar para activar */

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}