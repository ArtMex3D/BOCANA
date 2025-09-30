package com.cesar.bocana.ui.traspasos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentTraspasosContainerBinding
import com.cesar.bocana.ui.traspasos.plan.PlanificarTraspasoFragment
import com.google.android.material.tabs.TabLayout

class TraspasosContainerFragment : Fragment() {

    private var _binding: FragmentTraspasosContainerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTraspasosContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Gestión de Traspasos"

        // Cargar el fragmento inicial
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.traspasos_fragment_container, PlanificarTraspasoFragment())
                .commit()
        }

        binding.tabLayoutTraspasos.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> replaceFragment(PlanificarTraspasoFragment())
                    1 -> {
                        // Aquí irá el fragmento de Confirmar Traspaso en el futuro
                        Toast.makeText(context, "Confirmar Traspaso (Próximamente)", Toast.LENGTH_SHORT).show()
                        // Opcionalmente, puedes crear un fragmento placeholder
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun replaceFragment(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.traspasos_fragment_container, fragment)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
