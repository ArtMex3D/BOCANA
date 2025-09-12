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
import com.cesar.bocana.ui.report.ReportConfigFragment
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

        //boton para forzar sincronizacion, descomentar para activar
        binding.buttonForceSync.setOnClickListener {showForceSyncConfirmationDialog() }

        //boton para forzar mantenimiento, descomentar para activar
        binding.buttonMigrateData.setOnClickListener { showMigrationConfirmationDialog() }
    }

    // descomentar para activar
        private fun showForceSyncConfirmationDialog() {
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
                .setMessage("Corrigiendo estructura de datos. Por favor, espera.")
                .setCancelable(false)
                // .setView(R.layout.layout_loading_animation) // Opcional: si tienes una animación de carga
                .create()

            progressDialog.show()
            binding.buttonMigrateData.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Lista de campos válidos según tu Product.kt y la lista de Firestore
                    val validFields = setOf(
                        "id", "name", "unit", "minStock", "providerDetails",
                        "stockMatriz", "stockCongelador04", "totalStock", "createdAt",
                        "updatedAt", "lastUpdatedByName", "isActive", "requiresPackaging",
                        "stockIdealC04", "unidadDeEmpaque", "pesoPorUnidad",
                        "espacioExtraPDF", "modoManualPDF", "ordenTraspaso",
                        "tipoDeEmpaque", "labelConfig"
                    )

                    val productsCollection = firestore.collection("products")
                    val productsSnapshot = productsCollection.get().await()
                    var batch = firestore.batch()
                    var productsProcessed = 0
                    var batchCounter = 0
                    val errors = mutableListOf<String>()

                    for (document in productsSnapshot.documents) {
                        try {
                            val productRef = document.reference
                            val data = document.data ?: continue
                            val updates = mutableMapOf<String, Any?>()

                            // --- LIMPIEZA DE CAMPOS OBSOLETOS ---
                            data.keys.forEach { key ->
                                if (key !in validFields) {
                                    updates[key] = FieldValue.delete()
                                }
                            }

                            // --- ESTABLECER VALORES POR DEFECTO PARA CAMPOS FALTANTES ---
                            val defaultValues = mapOf(
                                "unit" to "Kg",
                                "minStock" to 0.0,
                                "providerDetails" to "",
                                "isActive" to true,
                                "requiresPackaging" to false,
                                "stockIdealC04" to 0.0,
                                "unidadDeEmpaque" to null,
                                "pesoPorUnidad" to 0.0,
                                "espacioExtraPDF" to 0.0,
                                "modoManualPDF" to false,
                                "ordenTraspaso" to 999, // Un número alto para que aparezcan al final
                                "tipoDeEmpaque" to null,
                                "labelConfig" to null
                            )

                            defaultValues.forEach { (field, defaultValue) ->
                                if (!data.containsKey(field)) {
                                    updates[field] = defaultValue
                                }
                            }

                            // --- CORRECCIÓN DE TIPOS DE DATOS (Ejemplo) ---
                            // Asegurarse que los campos numéricos sean Double o Long, no String.
                            listOf("minStock", "stockMatriz", "stockCongelador04", "totalStock", "stockIdealC04", "pesoPorUnidad", "espacioExtraPDF").forEach { field ->
                                if (data[field] is String) {
                                    updates[field] = (data[field] as String).toDoubleOrNull() ?: 0.0
                                    errors.add("${data["name"]}: Campo '$field' era String y se convirtió a número.")
                                }
                            }
                            if (data["ordenTraspaso"] is String) {
                                updates["ordenTraspaso"] = (data["ordenTraspaso"] as String).toIntOrNull() ?: 999
                                errors.add("${data["name"]}: Campo 'ordenTraspaso' era String y se convirtió a número.")
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
                                withContext(Dispatchers.Main) {
                                    progressDialog.setMessage("Procesados $productsProcessed productos...")
                                }
                            }

                        } catch (e: Exception) {
                            errors.add("Error procesando doc ${document.id}: ${e.message}")
                        }
                    }

                    if (batchCounter > 0) {
                        batch.commit().await()
                    }

                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        val message = buildString {
                            append(if (productsProcessed > 0) "¡Mantenimiento completado! " else "No se necesitaron cambios. ")
                            append("$productsProcessed productos actualizados/verificados.")
                            if (errors.isNotEmpty()) {
                                append("\nSe encontraron y corrigieron ${errors.size} problemas menores.")
                            }
                        }

                        AlertDialog.Builder(requireContext())
                            .setTitle("Resultado del Mantenimiento")
                            .setMessage(message)
                            .setPositiveButton("Aceptar", null)
                            .apply {
                                if (errors.isNotEmpty()) {
                                    setNeutralButton("Ver Detalles") { _, _ -> showFullErrorLog(errors) }
                                }
                            }
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
    //borrar para activar */

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}