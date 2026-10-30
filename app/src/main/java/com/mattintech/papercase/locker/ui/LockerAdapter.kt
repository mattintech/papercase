package com.mattintech.papercase.locker.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.databinding.ItemDocumentCardBinding
import com.mattintech.papercase.databinding.ItemFolderBinding
import com.mattintech.papercase.databinding.ItemSecureDocumentBinding
import com.mattintech.papercase.locker.model.DocumentIcons
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.reminders.ExpiryDates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class LockerItem {
    data class FolderItem(val folder: Folder, val folderCount: Int, val documentCount: Int) : LockerItem()
    data class DocItem(val doc: SecureDocument) : LockerItem()
}

/** The locker: folder tiles first, then documents, shown as list rows or thumbnail cards. */
class LockerAdapter(
    private val scope: CoroutineScope,
    private val thumbnails: ThumbnailLoader,
    private val onOpenFolder: (Folder) -> Unit,
    private val onFolderMore: (Folder, View) -> Unit,
    private val onOpenDocument: (SecureDocument) -> Unit,
    private val onDocumentMore: (SecureDocument) -> Unit,
    private val onCopyNumber: (SecureDocument) -> Unit
) : ListAdapter<LockerItem, RecyclerView.ViewHolder>(Diff) {

    var cards = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int) = when {
        getItem(position) is LockerItem.FolderItem -> TYPE_FOLDER
        cards -> TYPE_CARD
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOLDER -> FolderHolder(ItemFolderBinding.inflate(inflater, parent, false))
            TYPE_CARD -> CardHolder(ItemDocumentCardBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemSecureDocumentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LockerItem.FolderItem -> (holder as FolderHolder).bind(item)
            is LockerItem.DocItem -> when (holder) {
                is CardHolder -> holder.bind(item.doc)
                is RowHolder -> holder.bind(item.doc)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? CardHolder)?.job?.cancel()
    }

    private fun View.setMargins(horizontal: Int, bottom: Int) {
        val d = resources.displayMetrics.density
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (horizontal * d).toInt()
            it.marginEnd = (horizontal * d).toInt()
            it.topMargin = if (cards) (6 * d).toInt() else 0
            it.bottomMargin = (bottom * d).toInt()
            layoutParams = it
        }
    }

    inner class FolderHolder(private val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: LockerItem.FolderItem) {
            // In the grid, cards sit 6dp apart; in the list, rows line up with the documents
            if (cards) b.root.setMargins(6, 6) else b.root.setMargins(0, 12)
            b.tvFolderName.text = item.folder.name
            b.tvFolderCount.text = countText(item.folderCount, item.documentCount)
            b.root.setOnClickListener { onOpenFolder(item.folder) }
            b.btnMore.setOnClickListener { onFolderMore(item.folder, it) }
        }

        private fun countText(folders: Int, docs: Int): String {
            val parts = mutableListOf<String>()
            if (folders > 0) parts.add("$folders ${if (folders == 1) "folder" else "folders"}")
            if (docs > 0 || folders == 0) parts.add("$docs ${if (docs == 1) "document" else "documents"}")
            return parts.joinToString(", ")
        }
    }

    inner class RowHolder(private val b: ItemSecureDocumentBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(doc: SecureDocument) {
            b.tvDocTitle.text = doc.title
            DocumentIcons.apply(b.ivDocIcon, doc)
            b.ivPasswordLock.visibility = if (doc.isPasswordProtected) View.VISIBLE else View.GONE
            b.tvDocType.text = typeText(doc)

            if (doc.documentNumber.isNotBlank()) {
                b.layoutDocNumber.visibility = View.VISIBLE
                b.tvDocNumber.text = maskNumber(doc.documentNumber)
                b.btnCopyNumber.setOnClickListener { onCopyNumber(doc) }
            } else {
                b.layoutDocNumber.visibility = View.GONE
            }

            if (doc.expirationDate.isNotBlank()) {
                b.tvExpiration.visibility = View.VISIBLE
                val expired = expiryStatus(doc) == Expiry.EXPIRED
                b.tvExpiration.text = if (expired) "Expired: ${doc.expirationDate}" else "Expires: ${doc.expirationDate}"
                b.tvExpiration.setTextColor(
                    MaterialColors.getColor(b.tvExpiration,
                        if (expired) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            } else {
                b.tvExpiration.visibility = View.GONE
            }

            val summary = doc.getSummaryText()
            b.tvAttachmentBadge.visibility = if (summary.isNotBlank()) View.VISIBLE else View.GONE
            b.tvAttachmentBadge.text = summary

            b.root.setOnClickListener { onOpenDocument(doc) }
            b.btnMore.setOnClickListener { onDocumentMore(doc) }
        }

        private fun maskNumber(number: String): String {
            if (number.length <= 4) return number
            return "•".repeat((number.length - 4).coerceAtMost(8)) + " " + number.takeLast(4)
        }
    }

    inner class CardHolder(private val b: ItemDocumentCardBinding) : RecyclerView.ViewHolder(b.root) {
        var job: Job? = null

        fun bind(doc: SecureDocument) {
            b.tvDocTitle.text = doc.title
            b.tvDocType.text = typeText(doc)
            DocumentIcons.apply(b.ivDocIcon, doc)
            b.root.setOnClickListener { onOpenDocument(doc) }
            b.btnMore.setOnClickListener { onDocumentMore(doc) }
            bindExpiry(doc)

            // Placeholder until the thumbnail is ready; protected documents always keep it
            job?.cancel()
            b.ivThumb.setImageDrawable(null)
            b.ivPlaceholder.visibility = View.VISIBLE
            DocumentIcons.apply(
                b.ivPlaceholder,
                if (doc.isPasswordProtected) com.mattintech.papercase.R.drawable.ic_lock else DocumentIcons.iconRes(doc),
                doc.iconColor
            )
            if (doc.isPasswordProtected) return
            job = scope.launch {
                val bitmap = runCatching { thumbnails.load(doc) }.getOrNull() ?: return@launch
                b.ivThumb.setImageBitmap(bitmap)
                b.ivPlaceholder.visibility = View.GONE
            }
        }

        private fun bindExpiry(doc: SecureDocument) {
            val status = expiryStatus(doc)
            if (status == Expiry.NONE) {
                b.tvExpiryPill.visibility = View.GONE
                return
            }
            b.tvExpiryPill.visibility = View.VISIBLE
            b.tvExpiryPill.text = if (status == Expiry.EXPIRED) "Expired ${doc.expirationDate}" else "Expires ${doc.expirationDate}"
            val (bg, fg) = when (status) {
                Expiry.EXPIRED -> MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorErrorContainer) to
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnErrorContainer)
                Expiry.SOON -> 0xFFFFE08A.toInt() to 0xFF4A3800.toInt()
                else -> MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurfaceContainerHighest) to
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
            }
            b.tvExpiryPill.setTextColor(fg)
            b.tvExpiryPill.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(bg)
            }
        }
    }

    private enum class Expiry { NONE, OK, SOON, EXPIRED }

    /** Soon = inside the document's reminder window (or 30 days if it has no reminder). */
    private fun expiryStatus(doc: SecureDocument): Expiry {
        val date = ExpiryDates.parse(doc.expirationDate) ?: return Expiry.NONE
        val today = LocalDate.now()
        val window = if (doc.reminderDays > 0) doc.reminderDays.toLong() else 30L
        return when {
            date.isBefore(today) -> Expiry.EXPIRED
            !date.minusDays(window).isAfter(today) -> Expiry.SOON
            else -> Expiry.OK
        }
    }

    private fun typeText(doc: SecureDocument) =
        if (doc.issuingCountry.isNotBlank()) "${doc.type.displayName} • ${doc.issuingCountry}" else doc.type.displayName

    private object Diff : DiffUtil.ItemCallback<LockerItem>() {
        override fun areItemsTheSame(a: LockerItem, b: LockerItem) = when {
            a is LockerItem.FolderItem && b is LockerItem.FolderItem -> a.folder.id == b.folder.id
            a is LockerItem.DocItem && b is LockerItem.DocItem -> a.doc.id == b.doc.id
            else -> false
        }

        override fun areContentsTheSame(a: LockerItem, b: LockerItem) = a == b
    }

    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_ROW = 1
        const val TYPE_CARD = 2
    }
}
