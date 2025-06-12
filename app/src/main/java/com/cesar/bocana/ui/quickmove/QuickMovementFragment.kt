package com.cesar.bocana.ui.quickmove

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentQuickMovementBinding
import com.cesar.bocana.ui.dialogs.AjusteSubloteC04DialogFragment
import com.cesar.bocana.ui.dialogs.SalidaConsumoLotesDialogFragment
import com.cesar.bocana.ui.dialogs.TraspasoMatrizC04DialogFragment
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.DecimalFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class QuickMovementFragment : Fragment() {

    private var _binding: FragmentQuickMovementBinding? = null
    private val binding get() = _binding!!
    private var productId: String? = null
    private var product: Product? = null
    private val db = Firebase.firestore
    private val stockFormat = DecimalFormat("#,##0.##")

    companion object {
        private const val ARG_PRODUCT_ID = "product_id"
        fun newInstance(productId: String): QuickMovementFragment {
            return QuickMovementFragment().apply {
                arguments = Bundle().apply { putString(ARG_PRODUCT_ID, productId) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { productId = it.getString(ARG_PRODUCT_ID) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQuickMovementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Movimiento Rápido"

        loadProductData()

        binding.buttonSalidaConsumo.setOnClickListener {
            product?.let {
                SalidaConsumoLotesDialogFragment.newInstance(it)
                    .show(parentFragmentManager, SalidaConsumoLotesDialogFragment.TAG)
            }
        }

        binding.buttonTraspasoC04.setOnClickListener {
            product?.let {
                TraspasoMatrizC04DialogFragment.newInstance(it)
                    .show(parentFragmentManager, TraspasoMatrizC04DialogFragment.TAG)
            }
        }

        binding.buttonAjusteC04.setOnClickListener {
            product?.let {
                AjusteSubloteC04DialogFragment.newInstance(it.id)
                    .show(parentFragmentManager, AjusteSubloteC04DialogFragment.TAG)
            }
        }
    }

    private fun loadProductData() {
        val id = productId ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val document = db.collection(FirestoreCollections.PRODUCTS).document(id).get().await()
                product = document.toObject(Product::class.java)
                if (product != null && isAdded) {
                    bindData(product!!)
                } else if(isAdded) {
                    Toast.makeText(context, "Producto no encontrado.", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            } catch (e: Exception) {
                if(isAdded) {
                    Toast.makeText(context, "Error al cargar producto.", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            } finally {
                if (isAdded) {
                    binding.progressBar.visibility = View.GONE
                    binding.contentGroup.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun bindData(p: Product) {
        binding.productNameTitle.text = p.name
        binding.stockMatrizValue.text = "${stockFormat.format(p.stockMatriz)} ${p.unit}"
        binding.stockC04Value.text = "${stockFormat.format(p.stockCongelador04)} ${p.unit}"

        binding.buttonSalidaConsumo.isEnabled = p.stockMatriz > 0
        binding.buttonTraspasoC04.isEnabled = p.stockMatriz > 0
        binding.buttonAjusteC04.isEnabled = p.stockCongelador04 > 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}