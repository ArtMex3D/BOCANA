package com.cesar.bocana.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.data.model.PdfPage
import com.cesar.bocana.databinding.ItemPdfPageBinding
import com.github.chrisbanes.photoview.PhotoView

class PdfPageAdapter(private val pages: List<PdfPage>) : RecyclerView.Adapter<PdfPageAdapter.PdfPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PdfPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class PdfPageViewHolder(private val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(page: PdfPage) {
            // Se utiliza el PhotoView en lugar del ImageView
            (binding.pageImageView as PhotoView).setImageBitmap(page.bitmap)
        }
    }
}

