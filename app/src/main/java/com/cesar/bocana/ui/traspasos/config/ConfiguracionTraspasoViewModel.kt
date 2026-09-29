package com.cesar.bocana.ui.traspasos.config

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.TransferPdfConfig
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConfiguracionTraspasoViewModel(application: Application) : AndroidViewModel(application) {

    private val localDb = AppDatabase.getDatabase(application.applicationContext)
    private val productDao = localDb.productDao()
    private val pdfConfigDao = localDb.transferPdfConfigDao()
    private val firestore = Firebase.firestore

    val products: StateFlow<List<Product>> = productDao
        .getActiveProductsByTransferOrderStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pdfConfig: StateFlow<TransferPdfConfig> = pdfConfigDao
        .observe()
        .map { (it ?: TransferPdfConfig.defaults()).normalized() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TransferPdfConfig.defaults()
        )

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        bootstrapPdfConfig()
    }

    /**
     * Primera actualización: conserva los colores antiguos del teléfono que
     * migra primero. Si Firestore ya tiene configuración, siempre gana la nube.
     */
    private fun bootstrapPdfConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // El listener global ya mantiene Room al día. Si hay caché local,
                // abrir esta pantalla no debe provocar otra lectura de Firestore.
                if (pdfConfigDao.getOnce() != null) {
                    return@launch
                }

                val remoteRef = firestore
                    .collection(TransferPdfConfig.COLLECTION)
                    .document(TransferPdfConfig.DOCUMENT_ID)
                val remote = withTimeout(REMOTE_WRITE_TIMEOUT_MS) { remoteRef.get().await() }

                if (remote.exists()) {
                    val config = TransferPdfConfig(
                        titleText = remote.getString("titleText")
                            ?: TransferPdfConfig.DEFAULT_TITLE,
                        headerBackgroundHex = remote.getString("headerBackgroundHex")
                            ?: TransferPdfConfig.DEFAULT_HEADER_BACKGROUND,
                        headerTextHex = remote.getString("headerTextHex")
                            ?: TransferPdfConfig.DEFAULT_HEADER_TEXT,
                        zebraHex = remote.getString("zebraHex")
                            ?: TransferPdfConfig.DEFAULT_ZEBRA,
                        updatedAtMillis = remote.getTimestamp("updatedAt")?.toDate()?.time
                            ?: remote.getLong("updatedAtMillis")
                            ?: System.currentTimeMillis()
                    ).normalized()
                    pdfConfigDao.upsert(config)
                } else {
                    // El listener puede haber insertado Room mientras terminaba el get().
                    val initial = pdfConfigDao.getOnce()?.normalized() ?: legacyOrDefaultConfig()
                    pdfConfigDao.upsert(initial)
                    withTimeout(REMOTE_WRITE_TIMEOUT_MS) {
                        remoteRef.set(initial.toFirestoreMap(), SetOptions.merge()).await()
                    }
                    Log.d("ConfigTraspasoVM", "Configuración PDF inicial publicada.")
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                // Sin red se conserva Room/SharedPreferences; el listener central
                // completará la sincronización al recuperar conectividad.
                if (pdfConfigDao.getOnce() == null) {
                    pdfConfigDao.upsert(legacyOrDefaultConfig())
                }
                Log.w("ConfigTraspasoVM", "Configuración PDF trabajando desde Room", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun legacyOrDefaultConfig(): TransferPdfConfig {
        val prefs = getApplication<Application>().getSharedPreferences(
            TransferPdfConfig.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        fun legacyHex(key: String, fallback: String): String {
            if (!prefs.contains(key)) return fallback
            val color = prefs.getInt(key, Color.parseColor(fallback))
            return String.format("#%06X", 0xFFFFFF and color)
        }

        return TransferPdfConfig(
            headerBackgroundHex = legacyHex(
                TransferPdfConfig.LEGACY_HEADER_BACKGROUND,
                TransferPdfConfig.DEFAULT_HEADER_BACKGROUND
            ),
            headerTextHex = legacyHex(
                TransferPdfConfig.LEGACY_HEADER_TEXT,
                TransferPdfConfig.DEFAULT_HEADER_TEXT
            ),
            zebraHex = legacyHex(
                TransferPdfConfig.LEGACY_ZEBRA,
                TransferPdfConfig.DEFAULT_ZEBRA
            ),
            updatedAtMillis = System.currentTimeMillis()
        ).normalized()
    }

    fun updateProductOrder(orderedProducts: List<Product>) {
        if (orderedProducts.isEmpty()) return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                withContext(Dispatchers.IO) {
                    localDb.withTransaction {
                        orderedProducts.forEachIndexed { index, product ->
                            productDao.updateTransferOrder(product.id, index)
                        }
                    }
                }

                val batch = firestore.batch()
                orderedProducts.forEachIndexed { index, product ->
                    batch.update(
                        firestore.collection("products").document(product.id),
                        "ordenTraspaso",
                        index
                    )
                }
                withTimeout(REMOTE_WRITE_TIMEOUT_MS) { batch.commit().await() }
                Log.d("ConfigTraspasoVM", "Orden sincronizado: ${orderedProducts.size} productos.")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.e("ConfigTraspasoVM", "Error actualizando el orden", e)
                _error.value = "El orden quedó guardado localmente y se reintentará al sincronizar."
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateProductConfig(productId: String, field: String, value: Any) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val normalizedValue: Any = withContext(Dispatchers.IO) {
                    when (field) {
                        "modoManualPDF" -> {
                            val enabled = value as Boolean
                            productDao.updatePdfManualMode(productId, enabled)
                            enabled
                        }
                        "stockIdealC04" -> {
                            val stockIdeal = (value as Number).toDouble().coerceAtLeast(0.0)
                            productDao.updateStockIdealC04(productId, stockIdeal)
                            stockIdeal
                        }
                        "espacioExtraPDF" -> {
                            val extra = (value as Number).toDouble().coerceIn(0.0, 10.0)
                            productDao.updatePdfExtraSpace(productId, extra)
                            extra
                        }
                        else -> throw IllegalArgumentException("Campo no permitido: $field")
                    }
                }

                withTimeout(REMOTE_WRITE_TIMEOUT_MS) {
                    firestore.collection("products").document(productId)
                        .update(field, normalizedValue)
                        .await()
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.e("ConfigTraspasoVM", "Error actualizando campo '$field'", e)
                _error.value = "El cambio quedó en el respaldo local; revisa la conexión."
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun savePdfConfig(newConfig: TransferPdfConfig) {
        val normalized = newConfig.normalized().copy(updatedAtMillis = System.currentTimeMillis())
        viewModelScope.launch {
            _isSaving.value = true
            try {
                withContext(Dispatchers.IO) { pdfConfigDao.upsert(normalized) }
                withTimeout(REMOTE_WRITE_TIMEOUT_MS) {
                    firestore.collection(TransferPdfConfig.COLLECTION)
                        .document(TransferPdfConfig.DOCUMENT_ID)
                        .set(normalized.toFirestoreMap(), SetOptions.merge())
                        .await()
                }
                Log.d("ConfigTraspasoVM", "Diseño PDF sincronizado.")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.e("ConfigTraspasoVM", "Error sincronizando diseño PDF", e)
                _error.value = "El diseño está guardado en este equipo; falta sincronizar con la nube."
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun resetPdfConfig() {
        savePdfConfig(TransferPdfConfig.defaults())
    }

    fun consumeError() {
        _error.value = null
    }

    private fun TransferPdfConfig.toFirestoreMap(): Map<String, Any> = mapOf(
        "titleText" to titleText,
        "headerBackgroundHex" to headerBackgroundHex,
        "headerTextHex" to headerTextHex,
        "zebraHex" to zebraHex,
        "updatedAt" to FieldValue.serverTimestamp(),
        "updatedAtMillis" to updatedAtMillis
    )

    private companion object {
        const val REMOTE_WRITE_TIMEOUT_MS = 8_000L
    }
}
