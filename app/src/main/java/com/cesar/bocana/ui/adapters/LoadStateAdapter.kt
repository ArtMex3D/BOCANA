package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter as PagingLoadStateAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.databinding.ItemLoadStateBinding

class LoadStateAdapter(private val retry: () -> Unit) : PagingLoadStateAdapter<LoadStateAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemLoadStateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // El listener para el botón de reintento
            binding.buttonRetry.setOnClickListener {
                retry.invoke()
            }
        }

        /**
         * Vincula los datos del estado de carga a la vista.
         * AHORA SE USA binding.apply para acceder a las vistas correctamente.
         */
        fun bind(loadState: LoadState) {
            binding.apply {
                progressBar.isVisible = loadState is LoadState.Loading
                buttonRetry.isVisible = loadState is LoadState.Error
                textViewError.isVisible = loadState is LoadState.Error

                if (loadState is LoadState.Error) {
                    textViewError.text = loadState.error.localizedMessage
                }
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        holder.bind(loadState)
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val binding = ItemLoadStateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
}