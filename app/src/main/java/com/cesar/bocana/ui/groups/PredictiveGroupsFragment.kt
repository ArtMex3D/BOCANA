package com.cesar.bocana.ui.groups

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentPredictiveGroupsBinding
import com.cesar.bocana.predictive.v3.data.PredictiveGroupAdminRepository
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Fase 5.2 — Administración de grupos.
 *
 * Una sola entrada "Agregar grupo".
 * Internamente puede ser:
 * 1) Cobertura conjunta (Filetes, Pargos, etc.)
 * 2) Equilibrio complementario (Róbalo + Pargos)
 *
 * No existe un botón separado de "crear relación": la función/relación forma parte
 * de la configuración del propio grupo.
 */
class PredictiveGroupsFragment : Fragment() {

    private var _binding: FragmentPredictiveGroupsBinding? = null
    private val binding get() = _binding!!

    private val firestore = Firebase.firestore
    private lateinit var repository: PredictiveGroupAdminRepository

    private var bundle = PredictiveGroupAdminRepository.AdminBundle(
        activeProducts = emptyList(),
        groups = emptyList(),
        balances = emptyList()
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPredictiveGroupsBinding.inflate(inflater, container, false)
        repository = PredictiveGroupAdminRepository(firestore)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Grupos y prioridades"

        binding.buttonAddGroup.setOnClickListener { showNewGroupTypeDialog() }
        loadData(cleanInactive = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadData(cleanInactive: Boolean) {
        if (_binding == null) return
        binding.progressGroups.visibility = View.VISIBLE
        binding.buttonAddGroup.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var loaded = repository.load()

                if (cleanInactive) {
                    val activeIds = loaded.activeProducts.map { it.id }.toSet()
                    val changed = repository.cleanupInactiveMembers(activeIds)
                    if (changed) loaded = repository.load()
                }

                bundle = loaded
                renderGroups()
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "No se pudieron cargar los grupos: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (_binding != null) {
                    binding.progressGroups.visibility = View.GONE
                    binding.buttonAddGroup.isEnabled = true
                }
            }
        }
    }

    private fun renderGroups() {
        val container = binding.groupListContainer
        container.removeAllViews()

        val allItems = buildList {
            bundle.groups
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .forEach { add(AdminItem.Joint(it)) }

            bundle.balances
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .forEach { add(AdminItem.Balance(it)) }
        }

        binding.textEmptyGroups.visibility =
            if (allItems.isEmpty()) View.VISIBLE else View.GONE

        allItems.forEach { item ->
            container.addView(createGroupCard(item))
        }
    }

    private fun createGroupCard(item: AdminItem): View {
        val groupName: String
        val badge: String
        val summary: String

        when (item) {
            is AdminItem.Joint -> {
                groupName = item.group.name
                badge = "COBERTURA CONJUNTA"

                val productNames = item.group.memberProductIds
                    .mapNotNull(::productName)
                    .ifEmpty { listOf("Sin productos activos") }

                val primary = item.group.primaryProductId?.let(::productName)
                val secondary = item.group.secondaryProductIds.mapNotNull(::productName)

                summary = buildString {
                    append(productNames.joinToString(" · "))
                    if (!primary.isNullOrBlank()) append("\nPrincipal: $primary")
                    if (secondary.isNotEmpty()) {
                        append("\nSecundarios: ${secondary.joinToString(", ")}")
                    }
                }
            }

            is AdminItem.Balance -> {
                groupName = item.relation.name
                badge = "EQUILIBRIO"

                val anchors = item.relation.effectiveAnchorProductIds()
                    .mapNotNull(::productName)
                val linked = bundle.groups.firstOrNull { it.id == item.relation.linkedGroupId }
                val preferred = item.relation.preferredGroupProductId?.let(::productName)

                summary = buildString {
                    append(
                        "${anchors.ifEmpty { listOf("Sin producto directo") }.joinToString(" + ")}" +
                            " ↔ ${linked?.name ?: "Grupo no disponible"}"
                    )
                    if (!preferred.isNullOrBlank()) {
                        append("\nApoyo principal del grupo: $preferred")
                    }
                }
            }
        }

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#D7DCEF")
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(12))
        }

        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val titleWrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        titleWrap.addView(TextView(requireContext()).apply {
            text = groupName
            textSize = 17f
            setTextColor(Color.parseColor("#0B185B"))
            setTypeface(typeface, Typeface.BOLD)
        })

        titleWrap.addView(TextView(requireContext()).apply {
            text = badge
            textSize = 9f
            setTextColor(
                if (badge == "EQUILIBRIO") Color.parseColor("#0F766E")
                else Color.parseColor("#6D28D9")
            )
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(3), 0, 0)
        })

        top.addView(titleWrap)

        top.addView(TextView(requireContext()).apply {
            text = "✎"
            textSize = 21f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when (item) {
                    is AdminItem.Joint -> showJointEditor(item.group)
                    is AdminItem.Balance -> showBalanceEditor(item.relation)
                }
            }
        })

        top.addView(TextView(requireContext()).apply {
            text = "🗑"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showDeleteConfirmation(
                    id = when (item) {
                        is AdminItem.Joint -> item.group.id
                        is AdminItem.Balance -> item.relation.id
                    },
                    name = groupName,
                    isJoint = item is AdminItem.Joint
                )
            }
        })

        root.addView(top)

        root.addView(TextView(requireContext()).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.parseColor("#5F6B7A"))
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.12f)
        })

        card.addView(root)

        card.setOnClickListener {
            when (item) {
                is AdminItem.Joint -> showJointEditor(item.group)
                is AdminItem.Balance -> showBalanceEditor(item.relation)
            }
        }

        return card
    }

    private fun showNewGroupTypeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Agregar grupo")
            .setItems(
                arrayOf(
                    "Cobertura conjunta",
                    "Equilibrio entre producto y grupo"
                )
            ) { _, which ->
                if (which == 0) showJointEditor(null)
                else showBalanceEditor(null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Ejemplos:
     * Filetes = Lengua + Curvina + futuros filetes.
     * Pargos = HO + HM + RO + RM + VJ + futuros miembros.
     */
    private fun showJointEditor(existing: PredictiveGroupConfig?) {
        val otherUsedIds = bundle.groups
            .filter { it.id != existing?.id }
            .flatMap { it.memberProductIds }
            .toSet()

        val availableProducts = bundle.activeProducts
            .filter { !otherUsedIds.contains(it.id) || existing?.memberProductIds?.contains(it.id) == true }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }

        if (availableProducts.isEmpty()) {
            Toast.makeText(requireContext(), "No hay productos disponibles para otro grupo.", Toast.LENGTH_LONG).show()
            return
        }

        val selectedIds = existing?.memberProductIds?.toMutableSet() ?: linkedSetOf()
        var primaryId = existing?.primaryProductId
        val secondaryIds = existing?.secondaryProductIds?.toMutableSet() ?: linkedSetOf()

        val view = editorBase()
        val nameInput = EditText(requireContext()).apply {
            hint = "Nombre del grupo"
            setText(existing?.name.orEmpty())
            textSize = 15f
            setSingleLine(true)
        }
        view.addView(nameInput)

        val selectedSummary = editorSummary()
        val primarySummary = editorSummary()
        val secondarySummary = editorSummary()

        fun refresh() {
            val selectedNames = availableProducts
                .filter { selectedIds.contains(it.id) }
                .map { it.name }

            selectedSummary.text = if (selectedNames.isEmpty()) {
                "Productos: ninguno"
            } else {
                "Productos: ${selectedNames.joinToString(", ")}"
            }

            primarySummary.text = "Principal: ${primaryId?.let(::productName) ?: "Sin principal"}"

            val secondNames = secondaryIds.mapNotNull(::productName)
            secondarySummary.text = if (secondNames.isEmpty()) {
                "Secundarios: ninguno"
            } else {
                "Secundarios: ${secondNames.joinToString(", ")}"
            }
        }

        view.addView(editorButton("Elegir productos") {
            val labels = availableProducts.map { it.name }.toTypedArray()
            val checks = availableProducts.map { selectedIds.contains(it.id) }.toBooleanArray()

            AlertDialog.Builder(requireContext())
                .setTitle("Productos del grupo")
                .setMultiChoiceItems(labels, checks) { _, which, checked ->
                    val id = availableProducts[which].id
                    if (checked) selectedIds.add(id)
                    else {
                        selectedIds.remove(id)
                        if (primaryId == id) primaryId = null
                        secondaryIds.remove(id)
                    }
                }
                .setPositiveButton("Aceptar") { _, _ ->
                    primaryId = primaryId?.takeIf { selectedIds.contains(it) }
                    secondaryIds.retainAll(selectedIds)
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        view.addView(selectedSummary)

        view.addView(editorButton("Elegir principal") {
            val selected = availableProducts.filter { selectedIds.contains(it.id) }
            if (selected.isEmpty()) {
                toast("Primero elige los productos.")
                return@editorButton
            }

            val labels = arrayOf("Sin principal") + selected.map { it.name }
            val current = if (primaryId == null) 0
            else selected.indexOfFirst { it.id == primaryId }.let { if (it < 0) 0 else it + 1 }

            AlertDialog.Builder(requireContext())
                .setTitle("Producto principal")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    primaryId = if (which == 0) null else selected[which - 1].id
                    primaryId?.let { secondaryIds.remove(it) }
                    dialog.dismiss()
                    refresh()
                }
                .show()
        })
        view.addView(primarySummary)

        view.addView(editorButton("Elegir secundarios") {
            val selected = availableProducts.filter {
                selectedIds.contains(it.id) && it.id != primaryId
            }
            if (selected.isEmpty()) {
                toast("No hay productos disponibles como secundarios.")
                return@editorButton
            }

            val labels = selected.map { it.name }.toTypedArray()
            val checks = selected.map { secondaryIds.contains(it.id) }.toBooleanArray()

            AlertDialog.Builder(requireContext())
                .setTitle("Productos secundarios")
                .setMultiChoiceItems(labels, checks) { _, which, checked ->
                    val id = selected[which].id
                    if (checked) secondaryIds.add(id) else secondaryIds.remove(id)
                }
                .setPositiveButton("Aceptar") { _, _ ->
                    secondaryIds.retainAll(selectedIds)
                    primaryId?.let { secondaryIds.remove(it) }
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        view.addView(secondarySummary)

        view.addView(TextView(requireContext()).apply {
            text = "El principal tiene preferencia suave. FIFO, stock disponible y necesidad C04 siguen mandando."
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(10), 0, 0)
        })

        refresh()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Nuevo grupo" else "Editar grupo")
            .setView(wrapInScroll(view))
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    nameInput.error = "Escribe un nombre."
                    return@setOnClickListener
                }
                if (selectedIds.isEmpty()) {
                    toast("Selecciona al menos un producto.")
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.saveJointGroup(
                            existingId = existing?.id,
                            name = name,
                            memberProductIds = selectedIds.toList(),
                            primaryProductId = primaryId,
                            secondaryProductIds = secondaryIds.toList()
                        )
                        dialog.dismiss()
                        loadData(cleanInactive = false)
                    } catch (e: Exception) {
                        toast(e.message ?: "No se pudo guardar el grupo.")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * El usuario sigue viendo un GRUPO.
     * Ejemplo: Róbalo + Pargos.
     *
     * La relación es directa, pero no convierte 1 kg faltante en 1 kg del otro.
     * La presión adicional la aprende el motor del histórico.
     */
    private fun showBalanceEditor(existing: PredictiveServiceRelation?) {
        if (bundle.groups.isEmpty()) {
            toast("Primero crea al menos un grupo de cobertura conjunta.")
            return
        }

        val selectedAnchorIds = existing?.effectiveAnchorProductIds()?.toMutableSet() ?: linkedSetOf()
        var primaryAnchorId = existing?.effectivePrimaryAnchorId()
        var linkedGroupId = existing?.linkedGroupId.orEmpty()
        var preferredProductId = existing?.preferredGroupProductId

        val view = editorBase()
        val nameInput = EditText(requireContext()).apply {
            hint = "Nombre del grupo"
            setText(existing?.name.orEmpty())
            textSize = 15f
            setSingleLine(true)
        }
        view.addView(nameInput)

        val anchorSummary = editorSummary()
        val primarySummary = editorSummary()
        val linkedSummary = editorSummary()
        val preferredSummary = editorSummary()

        fun linkedGroup(): PredictiveGroupConfig? =
            bundle.groups.firstOrNull { it.id == linkedGroupId }

        fun refresh() {
            val anchorNames = selectedAnchorIds.mapNotNull(::productName)
            anchorSummary.text = if (anchorNames.isEmpty()) {
                "Producto directo: ninguno"
            } else {
                "Producto directo: ${anchorNames.joinToString(" + ")}"
            }

            primarySummary.text =
                "Principal directo: ${primaryAnchorId?.let(::productName) ?: "Sin principal"}"

            linkedSummary.text =
                "Grupo relacionado: ${linkedGroup()?.name ?: "Ninguno"}"

            preferredSummary.text =
                "Apoyo principal: ${preferredProductId?.let(::productName) ?: "Sin preferencia"}"
        }

        view.addView(editorButton("Elegir producto directo") {
            val products = bundle.activeProducts
            val labels = products.map { it.name }.toTypedArray()
            val checks = products.map { selectedAnchorIds.contains(it.id) }.toBooleanArray()

            AlertDialog.Builder(requireContext())
                .setTitle("Producto directo")
                .setMultiChoiceItems(labels, checks) { _, which, checked ->
                    val id = products[which].id
                    if (checked) selectedAnchorIds.add(id)
                    else {
                        selectedAnchorIds.remove(id)
                        if (primaryAnchorId == id) primaryAnchorId = null
                    }
                }
                .setPositiveButton("Aceptar") { _, _ ->
                    primaryAnchorId = primaryAnchorId?.takeIf { selectedAnchorIds.contains(it) }
                        ?: selectedAnchorIds.firstOrNull()
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        view.addView(anchorSummary)

        view.addView(editorButton("Elegir principal directo") {
            val selected = bundle.activeProducts.filter { selectedAnchorIds.contains(it.id) }
            if (selected.isEmpty()) {
                toast("Primero elige el producto directo.")
                return@editorButton
            }

            val labels = selected.map { it.name }.toTypedArray()
            val current = selected.indexOfFirst { it.id == primaryAnchorId }.coerceAtLeast(0)

            AlertDialog.Builder(requireContext())
                .setTitle("Principal directo")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    primaryAnchorId = selected[which].id
                    dialog.dismiss()
                    refresh()
                }
                .show()
        })
        view.addView(primarySummary)

        view.addView(editorButton("Elegir grupo relacionado") {
            val groups = bundle.groups
            val labels = groups.map { it.name }.toTypedArray()
            val current = groups.indexOfFirst { it.id == linkedGroupId }

            AlertDialog.Builder(requireContext())
                .setTitle("Grupo relacionado")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    linkedGroupId = groups[which].id

                    // Si el producto directo también vive dentro del grupo relacionado,
                    // se permite editar, pero no guardar hasta corregirlo.
                    preferredProductId = preferredProductId
                        ?.takeIf { groups[which].memberProductIds.contains(it) }

                    dialog.dismiss()
                    refresh()
                }
                .show()
        })
        view.addView(linkedSummary)

        view.addView(editorButton("Elegir apoyo principal") {
            val group = linkedGroup()
            if (group == null) {
                toast("Primero elige el grupo relacionado.")
                return@editorButton
            }

            val products = group.memberProductIds
                .mapNotNull { id -> bundle.activeProducts.firstOrNull { it.id == id } }

            if (products.isEmpty()) {
                toast("Ese grupo no tiene productos activos.")
                return@editorButton
            }

            val labels = arrayOf("Sin preferencia") + products.map { it.name }
            val current = if (preferredProductId == null) 0
            else products.indexOfFirst { it.id == preferredProductId }
                .let { if (it < 0) 0 else it + 1 }

            AlertDialog.Builder(requireContext())
                .setTitle("Apoyo principal del grupo")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    preferredProductId = if (which == 0) null else products[which - 1].id
                    dialog.dismiss()
                    refresh()
                }
                .show()
        })
        view.addView(preferredSummary)

        view.addView(TextView(requireContext()).apply {
            text = "El motor observa si el producto directo baja y si históricamente el grupo aumenta. No convierte faltantes kilo por kilo."
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(10), 0, 0)
        })

        refresh()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Nuevo grupo de equilibrio" else "Editar grupo de equilibrio")
            .setView(wrapInScroll(view))
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()

                if (name.isBlank()) {
                    nameInput.error = "Escribe un nombre."
                    return@setOnClickListener
                }
                if (selectedAnchorIds.isEmpty()) {
                    toast("Selecciona el producto directo.")
                    return@setOnClickListener
                }

                val linked = linkedGroup()
                if (linked == null) {
                    toast("Selecciona el grupo relacionado.")
                    return@setOnClickListener
                }

                val conflict = selectedAnchorIds.firstOrNull { linked.memberProductIds.contains(it) }
                if (conflict != null) {
                    toast("${productName(conflict)} no puede estar en ambos lados de este equilibrio.")
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.saveBalanceGroup(
                            existingId = existing?.id,
                            name = name,
                            anchorProductIds = selectedAnchorIds.toList(),
                            primaryAnchorProductId = primaryAnchorId,
                            linkedGroupId = linked.id,
                            preferredGroupProductId = preferredProductId
                        )
                        dialog.dismiss()
                        loadData(cleanInactive = false)
                    } catch (e: Exception) {
                        toast(e.message ?: "No se pudo guardar el grupo.")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showDeleteConfirmation(id: String, name: String, isJoint: Boolean) {
        val dependentCount = if (isJoint) {
            bundle.balances.count { it.linkedGroupId == id }
        } else 0

        val extra = if (dependentCount > 0) {
            "\n\nTambién se eliminarán $dependentCount configuración(es) de equilibrio que dependen de este grupo."
        } else ""

        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar grupo")
            .setMessage(
                "¿Eliminar “$name” de la configuración?$extra\n\n" +
                    "No se borrarán productos, lotes, movimientos ni históricos."
            )
            .setPositiveButton("Eliminar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.deleteConfig(id)
                        loadData(cleanInactive = false)
                    } catch (e: Exception) {
                        toast("No se pudo eliminar: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editorBase(): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

    private fun editorSummary(): TextView =
        TextView(requireContext()).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

    private fun editorButton(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#0B185B"))
            setBackgroundColor(Color.parseColor("#EEF1FF"))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(10)
            }
        }

    private fun wrapInScroll(content: View): ScrollView =
        ScrollView(requireContext()).apply {
            addView(content)
        }

    private fun productName(productId: String): String? =
        bundle.activeProducts.firstOrNull { it.id == productId }?.name

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private sealed class AdminItem {
        data class Joint(val group: PredictiveGroupConfig) : AdminItem()
        data class Balance(val relation: PredictiveServiceRelation) : AdminItem()
    }
}
