package com.cesar.bocana.ui.packaging

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.FragmentPackagingBinding
import com.cesar.bocana.predictive.v3.PredictiveV3Manager
import com.cesar.bocana.predictive.v3.data.PredictiveV3Snapshot
import com.cesar.bocana.ui.adapters.PackagingActionListener
import com.cesar.bocana.ui.adapters.PackagingAdapter
import com.cesar.bocana.ui.adapters.PackagingUiItem
import com.cesar.bocana.ui.adapters.PackagingVisualLevel
import com.cesar.bocana.ui.dialogs.EmpaqueDialogFragment
import com.cesar.bocana.ui.printing.EtiquetasMenuFragment
import com.cesar.bocana.ui.printing.LabelType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PackagingFragment : Fragment(), PackagingActionListener, MenuProvider {

    private var _binding: FragmentPackagingBinding? = null
    private val binding get() = _binding!!

    private lateinit var packagingAdapter: PackagingAdapter
    private lateinit var firestore: FirebaseFirestore
    private lateinit var predictiveManager: PredictiveV3Manager
    private var packagingListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null

    private var pendingTasks: List<PendingPackagingTask> = emptyList()
    private var snapshotsByProduct: Map<String, PredictiveV3Snapshot> = emptyMap()
    private var actualQuantityByMovement: Map<String, Double> = emptyMap()
    private var previousTaskIds: Set<String> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPackagingBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        predictiveManager = PredictiveV3Manager.getInstance(requireContext())
        setupRecyclerView()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        observePredictiveSnapshots()
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
            title = "Pendiente de empacar"
            subtitle = "Preparación y etiquetado"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
            subtitle = null
        }
        originalActivityTitle = null
    }

    private fun setupRecyclerView() {
        packagingAdapter = PackagingAdapter(this)
        binding.recyclerViewPackaging.apply {
            adapter = packagingAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }
    }

    private fun observePredictiveSnapshots() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                predictiveManager.observeSnapshots().collect { snapshots ->
                    snapshotsByProduct = snapshots.associateBy { it.productId }
                    renderTasks()
                }
            }
        }
    }

    private fun observePackagingTasks() {
        if (packagingListener != null) return

        showLoading(true)
        binding.textViewEmptyPackaging.visibility = View.GONE

        val query = firestore.collection("pendingPackaging")
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        packagingListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) return@addSnapshotListener
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando tareas de empaque", error)
                binding.textViewEmptyPackaging.text = "No se pudieron cargar los pendientes."
                binding.textViewEmptyPackaging.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            val newTasks = snapshots?.toObjects(PendingPackagingTask::class.java).orEmpty()
            val newIds = newTasks.mapTo(linkedSetOf()) { it.id }
            val removed = previousTaskIds.isNotEmpty() && previousTaskIds.any { it !in newIds }
            previousTaskIds = newIds
            pendingTasks = newTasks

            viewLifecycleOwner.lifecycleScope.launch {
                actualQuantityByMovement = loadCurrentLotQuantities(newTasks)
                renderTasks()
            }

            if (removed) {
                refreshPredictionAfterPackaging()
            }
        }
    }

    private suspend fun loadCurrentLotQuantities(tasks: List<PendingPackagingTask>): Map<String, Double> {
        val movementIds = tasks.mapNotNull { it.purchaseMovementId }.distinct()
        if (movementIds.isEmpty()) return emptyMap()

        return runCatching {
            val result = linkedMapOf<String, Double>()
            movementIds.chunked(30).forEach { chunk ->
                val docs = firestore.collection("inventoryLots")
                    .whereIn("movementIdIn", chunk)
                    .get()
                    .await()

                docs.documents.mapNotNull { it.toObject(StockLot::class.java) }
                    .groupBy { it.movementIdIn }
                    .forEach { (movementId, lots) ->
                        val sourceLot = lots.firstOrNull { !it.isPackaged && !it.isDepleted }
                            ?: lots.firstOrNull { !it.isPackaged }
                            ?: lots.firstOrNull()
                        if (sourceLot != null) {
                            result[movementId] = sourceLot.currentQuantity.coerceAtLeast(0.0)
                        }
                    }
            }
            result
        }.onFailure {
            Log.w(TAG, "No se pudo resolver cantidad actual de algunos lotes; se conserva cantidad recibida.", it)
        }.getOrDefault(emptyMap())
    }

    private fun renderTasks() {
        if (_binding == null) return

        val now = System.currentTimeMillis()
        val uiItems = pendingTasks.map { task ->
            val snapshot = snapshotsByProduct[task.productId]
            val movementId = task.purchaseMovementId
            val actual = movementId?.let(actualQuantityByMovement::get) ?: task.quantityReceived
            val receivedMillis = task.receivedAt?.time
            val days = receivedMillis?.let { millis ->
                if (millis > now) 0L else TimeUnit.MILLISECONDS.toDays(now - millis)
            } ?: 0L

            val operationalAttention = snapshot?.let {
                it.suggestedTransferKg > 0.5 || ((it.coverageDays ?: Int.MAX_VALUE) <= 14)
            } == true

            val visualLevel = when {
                days >= 5L -> PackagingVisualLevel.RED
                operationalAttention || days >= 3L -> PackagingVisualLevel.AMBER
                days >= 1L -> PackagingVisualLevel.GREEN
                else -> PackagingVisualLevel.BLUE
            }

            val statusText = when {
                task.receivedAt == null -> "Fecha de llegada pendiente"
                days == 0L -> "Recibido hoy"
                days == 1L -> "Pendiente 1 día"
                days == 2L -> "Pendiente 2 días"
                else -> "Pendiente antiguo · $days días"
            }

            val insight = when {
                operationalAttention -> "Conviene prepararlo para próximos traspasos"
                snapshot != null -> "Sin urgencia operativa"
                else -> "Pendiente de preparación"
            }

            PackagingUiItem(
                task = task,
                actualQuantityKg = actual,
                statusText = statusText,
                insightText = insight,
                visualLevel = visualLevel,
                priority = operationalAttention
            )
        }.sortedWith(
            compareByDescending<PackagingUiItem> { it.priority }
                .thenBy { it.task.receivedAt?.time ?: Long.MAX_VALUE }
                .thenBy { it.task.productName.lowercase(Locale.getDefault()) }
        )

        packagingAdapter.submitList(uiItems)
        val hasTasks = uiItems.isNotEmpty()
        binding.textViewEmptyPackaging.visibility = if (hasTasks) View.GONE else View.VISIBLE
        binding.cardPackagingSummary.visibility = if (hasTasks) View.VISIBLE else View.GONE

        if (hasTasks) {
            val total = uiItems.sumOf { it.actualQuantityKg }
            val priorityCount = uiItems.count { it.priority }
            binding.textViewPackagingSummary.text = "${uiItems.size} tareas · ${formatQuantity(total)} kg pendientes"
            binding.textViewPackagingPriority.text = when (priorityCount) {
                0 -> "Todo en orden operativo"
                1 -> "1 pendiente requiere atención"
                else -> "$priorityCount pendientes requieren atención"
            }
        }
    }

    override fun onLabelsClicked(task: PendingPackagingTask) {
        val initialData = LabelData(
            labelType = LabelType.COSTAL,
            productId = task.productId,
            productName = task.productName,
            supplierName = task.supplierName,
            date = task.receivedAt ?: Date(),
            weight = "Manual",
            unit = task.unit.ifBlank { "Kg" },
            detail = null
        )

        parentFragmentManager.beginTransaction()
            .replace(
                R.id.nav_host_fragment_content_main,
                EtiquetasMenuFragment.newInstance(initialData, fromPackaging = true)
            )
            .addToBackStack(null)
            .commit()
    }

    override fun onMarkPackagedClicked(task: PendingPackagingTask) {
        EmpaqueDialogFragment.newInstance(task)
            .show(parentFragmentManager, EmpaqueDialogFragment.TAG)
    }

    private fun refreshPredictionAfterPackaging() {
        viewLifecycleOwner.lifecycleScope.launch {
            predictiveManager.clearSessionMemory()
            // Se da tiempo a que los listeners normales reflejen el cambio de lotes/tarea en Room.
            delay(1200)
            runCatching { predictiveManager.refreshAllIfNeeded() }
                .onFailure { Log.w(TAG, "La revisión silenciosa se hará en el siguiente ciclo.", it) }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarPackaging.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun formatQuantity(value: Double): String {
        val rounded = kotlin.math.round(value)
        return if (kotlin.math.abs(value - rounded) < 0.01) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {}
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    companion object {
        private const val TAG = "PackagingFragment"
    }
}
