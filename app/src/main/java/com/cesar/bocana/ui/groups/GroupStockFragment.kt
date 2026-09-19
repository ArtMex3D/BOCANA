package com.cesar.bocana.ui.groups

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.databinding.FragmentGroupStockBinding
import com.cesar.bocana.predictive.v3.data.PredictiveGroupAdminRepository
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Configuración de stock GRUPAL.
 *
 * Sólo muestra grupos de cobertura conjunta. Las relaciones de equilibrio
 * (ej. Róbalo ↔ Pargos) no aparecen porque conservan sus referencias individuales.
 */
class GroupStockFragment : Fragment() {

    private var _binding: FragmentGroupStockBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        PredictiveGroupAdminRepository(Firebase.firestore)
    }

    private var groups: List<PredictiveGroupConfig> = emptyList()
    private var productNames: Map<String, String> = emptyMap()

    private data class EditorRefs(
        val target: TextInputEditText,
        val primaryMinimum: TextInputEditText?
    )

    private val editors = linkedMapOf<String, EditorRefs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupStockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Stock grupal"

        binding.buttonSaveGroupStock.setOnClickListener { saveAll() }
        load()
    }

    private fun load() {
        binding.progressGroupStock.visibility = View.VISIBLE
        binding.buttonSaveGroupStock.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bundle = repository.load()
                groups = bundle.groups.sortedBy { it.name.lowercase(Locale.getDefault()) }
                productNames = bundle.activeProducts.associate { it.id to it.name }
                render()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "No se pudo cargar el stock grupal: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (_binding != null) {
                    binding.progressGroupStock.visibility = View.GONE
                    binding.buttonSaveGroupStock.isEnabled = true
                }
            }
        }
    }

    private fun render() {
        val container = binding.groupStockContainer
        container.removeAllViews()
        editors.clear()

        if (groups.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Primero crea un grupo de cobertura conjunta en “Grupos y prioridades”."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }

        groups.forEach { group ->
            val card = MaterialCardView(requireContext()).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(Color.WHITE)
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#E2E8F0")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            val body = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            body.addView(TextView(requireContext()).apply {
                text = group.name
                textSize = 17f
                setTextColor(Color.parseColor("#020961"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            val primaryName = group.primaryProductId?.let(productNames::get)
            body.addView(TextView(requireContext()).apply {
                text = if (primaryName != null) {
                    "Rector: $primaryName"
                } else {
                    "Cobertura conjunta sin rector obligatorio"
                }
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(2), 0, dp(10))
            })

            val targetLayout = inputLayout("Objetivo habitual C04 del grupo (kg)")
            val targetEdit = inputEdit(group.c04GroupTargetKg)
            targetLayout.addView(targetEdit)
            body.addView(targetLayout)

            var minimumEdit: TextInputEditText? = null
            if (group.primaryProductId != null) {
                val minLayout = inputLayout("Mínimo del rector en C04 (kg)")
                minimumEdit = inputEdit(group.primaryMinimumC04Kg)
                minLayout.addView(minimumEdit)
                minLayout.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                body.addView(minLayout)

                body.addView(TextView(requireContext()).apply {
                    text = "El mínimo del rector es operativo para este grupo; no reemplaza el stock mínimo general del producto."
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#7A8190"))
                    setPadding(dp(2), dp(6), dp(2), 0)
                })
            }

            editors[group.id] = EditorRefs(targetEdit, minimumEdit)
            card.addView(body)
            container.addView(card)
        }
    }

    private fun saveAll() {
        val changes = groups.mapNotNull { group ->
            val refs = editors[group.id] ?: return@mapNotNull null
            val target = refs.target.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            val minPrimary = refs.primaryMinimum?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0

            if (target < 0.0 || minPrimary < 0.0) {
                Toast.makeText(requireContext(), "Los valores no pueden ser negativos.", Toast.LENGTH_LONG).show()
                return
            }

            Triple(group.id, target, minPrimary)
        }

        binding.progressGroupStock.visibility = View.VISIBLE
        binding.buttonSaveGroupStock.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                changes.forEach { (groupId, target, minimum) ->
                    repository.saveGroupStockConfig(
                        groupId = groupId,
                        c04GroupTargetKg = target,
                        primaryMinimumC04Kg = minimum
                    )
                }
                Toast.makeText(requireContext(), "Stock grupal actualizado.", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "No se pudo guardar: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (_binding != null) {
                    binding.progressGroupStock.visibility = View.GONE
                    binding.buttonSaveGroupStock.isEnabled = true
                }
            }
        }
    }

    private fun inputLayout(hint: String): TextInputLayout =
        TextInputLayout(requireContext()).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun inputEdit(value: Double): TextInputEditText =
        TextInputEditText(requireContext()).apply {
            layoutParams = TextInputLayout.LayoutParams(
                TextInputLayout.LayoutParams.MATCH_PARENT,
                TextInputLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (value > 0.0) format(value) else "")
            setSelectAllOnFocus(true)
        }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
