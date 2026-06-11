package com.cesar.bocana.ui.masopciones

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
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
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.cesar.bocana.ui.traspasos.config.ConfiguracionTraspasoFragment
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class MoreOptionsFragment : Fragment() {

    private var _binding: FragmentMoreOptionsBinding? = null
    private val binding get() = _binding!!
    private val firestore = Firebase.firestore
    private lateinit var repository: InventoryRepository

    // Variables para el actualizador
    private var downloadId: Long = -1
    private var downloadedApkUri: Uri? = null
    private var updateApkUrl: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoreOptionsBinding.inflate(inflater, container, false)
        val database = AppDatabase.getDatabase(requireContext())
        repository = InventoryRepository(database, firestore)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // BOTONES DE NAVEGACIÓN NORMALES
        binding.buttonHistory.setOnClickListener { navigateTo(HistoryFragment(), "HistoryFragment") }
        binding.buttonAdvancedHistory.setOnClickListener { navigateTo(AdvancedHistoryFragment(), "AdvancedHistoryFragment") }
        binding.buttonNavToAjustes.setOnClickListener { navigateTo(AjustesFragment(), "AjustesFragment") }
        binding.buttonNavToArchivedProducts.setOnClickListener { navigateTo(ArchivedProductsFragment(), "ArchivedProductsFragment") }
        binding.buttonNavToReportGenerator.setOnClickListener { navigateTo(com.cesar.bocana.ui.report.ReportConfigFragment(), "ReportConfigFragment") }
        binding.buttonNavToDevoluciones.setOnClickListener { navigateTo(DevolucionesFragment(), "DevolucionesFragment") }
        binding.buttonNavToProveedores.setOnClickListener { navigateTo(SupplierListFragment(), "SupplierListFragment") }
        binding.buttonNavToConfigTraspasos.setOnClickListener { navigateTo(ConfiguracionTraspasoFragment(), "ConfiguracionTraspasoFragment") }

        // BOTONES OCULTOS (MANTENIMIENTO) CONECTADOS PARA EL FUTURO
        binding.buttonForceSync.setOnClickListener { showForceSyncConfirmationDialog() }
        binding.buttonMigrateData.setOnClickListener { showMigrationConfirmationDialog() }

        // 🚀 INICIA EL BUSCADOR DE ACTUALIZACIONES
        checkForUpdates()
    }

    private fun navigateTo(fragment: Fragment, tag: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(tag)
            .commit()
    }


    private fun checkForUpdates() {
        val currentVersionCode = try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }

        firestore.collection("app_config").document("update").get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val cloudVersionCode = document.getLong("versionCode")?.toInt() ?: 0
                    val cloudVersionName = document.getString("versionName") ?: "Nueva Versión"
                    val notes = document.getString("novedades") ?: "Mejoras de rendimiento."
                    updateApkUrl = document.getString("apkUrl") ?: ""

                    if (cloudVersionCode > currentVersionCode && updateApkUrl.isNotEmpty()) {
                        showUpdateBanner(cloudVersionName, notes)
                    }
                }
            }
            .addOnFailureListener { Log.e("Updater", "Error buscando actualización", it) }
    }

    private fun showUpdateBanner(versionName: String, notes: String) {
        binding.cardUpdateBanner.visibility = View.VISIBLE
        binding.tvUpdateVersion.text = versionName
        binding.tvUpdateNotes.text = notes

        binding.btnDownloadUpdate.setOnClickListener {
            startDynamicDownload(updateApkUrl)
        }
    }

    private fun startDynamicDownload(url: String) {
        binding.btnDownloadUpdate.isEnabled = false
        binding.btnDownloadUpdate.text = "Iniciando descarga..."
        binding.progressBarDownload.visibility = View.VISIBLE
        binding.tvDownloadStatus.visibility = View.VISIBLE
        binding.progressBarDownload.progress = 0

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Actualización de Bocana")
            setDescription("Descargando nueva versión...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Bocana_Update.apk")
        }

        val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val oldFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bocana_Update.apk")
        if (oldFile.exists()) oldFile.delete()

        downloadId = downloadManager.enqueue(request)
        trackDownloadProgress(downloadManager)
    }

    private fun trackDownloadProgress(downloadManager: DownloadManager) {
        lifecycleScope.launch(Dispatchers.IO) {
            var isDownloading = true
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                        val bytesTotal = cursor.getInt(bytesTotalIndex)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            isDownloading = false
                            val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            downloadedApkUri = Uri.parse(uriString)
                            withContext(Dispatchers.Main) { finishDownloadUi() }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            isDownloading = false
                            withContext(Dispatchers.Main) {
                                binding.btnDownloadUpdate.isEnabled = true
                                binding.btnDownloadUpdate.text = "Error. Reintentar."
                                binding.tvDownloadStatus.text = "La descarga falló."
                            }
                        } else {
                            if (bytesTotal > 0) {
                                val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()
                                withContext(Dispatchers.Main) {
                                    binding.progressBarDownload.progress = progress
                                    binding.tvDownloadStatus.text = "Descargando... $progress%"
                                }
                            }
                        }
                    }
                }
                cursor?.close()
                delay(500)
            }
        }
    }

    private fun finishDownloadUi() {
        binding.progressBarDownload.progress = 100
        binding.tvDownloadStatus.text = "¡Descarga completada!"
        binding.btnDownloadUpdate.isEnabled = true
        binding.btnDownloadUpdate.text = "Instalar Ahora"
        binding.btnDownloadUpdate.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        binding.btnDownloadUpdate.setIconResource(android.R.drawable.ic_menu_save)

        binding.btnDownloadUpdate.setOnClickListener { installApk() }
    }

    private fun installApk() {
        try {
            // 1. VERIFICAR PERMISO DE INSTALACIÓN AUTOMÁTICO (Android 8.0+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!requireContext().packageManager.canRequestPackageInstalls()) {
                    // Si no tiene el permiso, lo mandamos directo a la pantalla de ajustes para que lo active
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                    Toast.makeText(requireContext(), "Por favor, autoriza la instalación y vuelve a presionar 'Instalar Ahora'.", Toast.LENGTH_LONG).show()
                    return
                }
            }

            // 2. OBTENER LA RUTA OFICIAL DESDE EL DOWNLOAD MANAGER
            // (Esto esquiva el bloqueo de seguridad de Android y evita el 'Parse Error')
            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = downloadManager.getUriForDownloadedFile(downloadId)

            if (uri != null) {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(installIntent)
            } else {
                Toast.makeText(requireContext(), "Error: No se pudo obtener la ruta del archivo descargado.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("Updater", "Error al instalar APK", e)
            Toast.makeText(requireContext(), "Error al abrir el instalador.", Toast.LENGTH_LONG).show()
        }
    }


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

    private fun runForceSync() {
        val progressDialog = AlertDialog.Builder(requireContext())
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

                    data.keys.forEach { key ->
                        if (key !in validProductFields) {
                            updates[key] = FieldValue.delete()
                        }
                    }

                    if (!data.containsKey("requiresPackaging")) updates["requiresPackaging"] = false
                    if (!data.containsKey("stockIdealC04")) updates["stockIdealC04"] = 0.0

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
                if (batchCounter > 0) batch.commit().await()

                batch = firestore.batch()
                batchCounter = 0
                val lotsCollection = firestore.collection("inventoryLots")
                val lotsSnapshot = lotsCollection.get().await()
                var lotsProcessed = 0

                for (document in lotsSnapshot.documents) {
                    val lotRef = document.reference
                    val data = document.data ?: continue
                    val lotUpdates = mutableMapOf<String, Any?>()

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
                if (batchCounter > 0) batch.commit().await()

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}