package com.mattintech.papercase.locker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mattintech.papercase.R
import com.mattintech.papercase.databinding.ItemSecureDocumentBinding
import com.mattintech.papercase.locker.model.SecureDocument

class SecureDocumentsAdapter(
    private val onItemClick: (SecureDocument) -> Unit,
    private val onCopyNumber: (SecureDocument) -> Unit
) : ListAdapter<SecureDocument, SecureDocumentsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSecureDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSecureDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(doc: SecureDocument) {
            binding.tvDocTitle.text = doc.title
            binding.ivDocIcon.setImageResource(doc.type.iconRes)
            binding.ivPasswordLock.visibility = if (doc.isPasswordProtected) View.VISIBLE else View.GONE

            val typeDesc = if (doc.issuingCountry.isNotBlank()) {
                "${doc.type.displayName} • ${doc.issuingCountry}"
            } else {
                doc.type.displayName
            }
            binding.tvDocType.text = typeDesc

            if (doc.documentNumber.isNotBlank()) {
                binding.layoutDocNumber.visibility = View.VISIBLE
                binding.tvDocNumber.text = maskNumber(doc.documentNumber)
                binding.btnCopyNumber.setOnClickListener { onCopyNumber(doc) }
            } else {
                binding.layoutDocNumber.visibility = View.GONE
            }

            if (doc.expirationDate.isNotBlank()) {
                binding.tvExpiration.visibility = View.VISIBLE
                binding.tvExpiration.text = "Expires: ${doc.expirationDate}"
            } else {
                binding.tvExpiration.visibility = View.GONE
            }

            val summary = doc.getSummaryText()
            if (summary.isNotBlank()) {
                binding.tvAttachmentBadge.visibility = View.VISIBLE
                binding.tvAttachmentBadge.text = summary
            } else if (doc.attachments.isNotEmpty()) {
                val count = doc.attachments.size
                binding.tvAttachmentBadge.visibility = View.VISIBLE
                binding.tvAttachmentBadge.text = "$count ${if (count == 1) "attachment" else "attachments"}"
            } else {
                binding.tvAttachmentBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(doc) }
        }

        private fun maskNumber(number: String): String {
            if (number.length <= 4) return number
            val visiblePart = number.takeLast(4)
            val maskedPart = "•".repeat((number.length - 4).coerceAtMost(8))
            return "$maskedPart $visiblePart"
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SecureDocument>() {
        override fun areItemsTheSame(oldItem: SecureDocument, newItem: SecureDocument): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SecureDocument, newItem: SecureDocument): Boolean {
            return oldItem == newItem
        }
    }
}
