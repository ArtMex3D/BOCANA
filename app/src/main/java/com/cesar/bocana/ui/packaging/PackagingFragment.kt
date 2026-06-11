package com.cesar.bocana.ui.packaging

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.databinding.FragmentPackagingBinding
import com.cesar.bocana.ui.adapters.PackagingActionListener
import com.cesar.bocana.ui.adapters.PackagingAdapter
import com.cesar.bocana.ui.dialogs.EmpaqueDialogFragment
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class PackagingFragment : Fragment(), PackagingActionListener, MenuProvider {

    private var _binding: FragmentPackagingBinding? = null
    private val binding get() = _binding!!

    private lateinit var packagingAdapter: PackagingAdapter
    private lateinit var firestore: FirebaseFirestore
    private var packagingListener: ListenerRegistration? = null
    private var originalActivityTitle: CharSequence? = null
    private var pendingTasks: List<PendingPackagingTask> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPackagingBinding.inflate(inflater, container, false)
        firestore = Firebase.firestore
        setupRecyclerView()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
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
            subtitle = null
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun restoreToolbar() {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }
        originalActivityTitle = null
    }

    private fun setupRecyclerView() {
        packagingAdapter = PackagingAdapter(this)
        binding.recyclerViewPackaging.apply {
            adapter = packagingAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observePackagingTasks() {
        if (packagingListener != null) return

        showLoading(true)
        binding.textViewEmptyPackaging.visibility = View.GONE

        val query = firestore.collection("pendingPackaging")
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        packagingListener = query.addSnapshotListener { snapshots, error ->
            if (_binding == null || !isAdded) {
                packagingListener?.remove()
                packagingListener = null
                return@addSnapshotListener
            }
            showLoading(false)

            if (error != null) {
                Log.e(TAG, "Error escuchando tareas de empaque", error)
                binding.textViewEmptyPackaging.text = "Error al cargar tareas."
                binding.textViewEmptyPackaging.visibility = View.VISIBLE
                return@addSnapshotListener
            }

            if (snapshots != null) {
                pendingTasks = snapshots.toObjects(PendingPackagingTask::class.java)
                packagingAdapter.submitList(pendingTasks)
                binding.textViewEmptyPackaging.text = "No hay items pendientes de empacar."

                val hasTasks = pendingTasks.isNotEmpty()
                binding.textViewEmptyPackaging.visibility = if (hasTasks) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onMarkPackagedClicked(task: PendingPackagingTask) {
        EmpaqueDialogFragment.newInstance(task)
            .show(parentFragmentManager, EmpaqueDialogFragment.TAG)
    }

    private fun showLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBarPackaging.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onPrepareMenu(menu: Menu) {}
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean { return false }

    companion object {
        private const val TAG = "PackagingFragment"
    }
}
