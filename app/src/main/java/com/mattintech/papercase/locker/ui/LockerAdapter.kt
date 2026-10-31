package com.mattintech.papercase.locker.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.databinding.ItemDocumentCardBinding
import com.mattintech.papercase.databinding.ItemFolderBinding
import com.mattintech.papercase.databinding.ItemSecureDocumentBinding
import com.mattintech.papercase.locker.model.DocumentIcons
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.FolderPhoto
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.reminders.ExpiryDates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class LockerItem {
    /** [expiredCount]: expired documents anywhere inside, so they aren't missed in subfolders. */
    data class FolderItem(val folder: Folder, val folderCount: Int, val documentCount: Int, val expiredCount: Int = 0) : LockerItem()
    data class DocItem(val doc: SecureDocument) : LockerItem()
    /** Fills the rest of the last folder row in the grid, so documents start on a new row. */
    data object RowEnd : LockerItem()
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
        getItem(position) is LockerItem.RowEnd -> TYPE_ROW_END
        getItem(position) is LockerItem.FolderItem -> TYPE_FOLDER
        cards -> TYPE_CARD
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ROW_END -> object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(0, 0)
            }) {}
            TYPE_FOLDER -> FolderHolder(ItemFolderBinding.inflate(inflater, parent, false))
            TYPE_CARD -> CardHolder(ItemDocumentCardBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemSecureDocumentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LockerItem.RowEnd -> Unit
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
            bindFolderIcon(item.folder)
            val counts = countText(item.folderCount, item.documentCount)
            if (item.expiredCount > 0) {
                val expired = "${item.expiredCount} expired"
                b.tvFolderCount.text = android.text.SpannableString("$counts · $expired").apply {
                    setSpan(
                        android.text.style.ForegroundColorSpan(MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorError)),
                        length - expired.length, length, 0
                    )
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), length - expired.length, length, 0)
                }
            } else {
                b.tvFolderCount.text = counts
            }
            b.root.setOnClickListener { onOpenFolder(item.folder) }
            b.btnMore.setOnClickListener { onFolderMore(item.folder, it) }
        }

        /** The folder's photo, the icon and colour picked for it (e.g. a child's face), or the plain folder icon. */
        private fun bindFolderIcon(folder: Folder) {
            val view = b.ivFolderIcon
            val d = view.resources.displayMetrics.density
            val photo = folder.photo.takeIf { it.isNotEmpty() }?.let(FolderPhoto::decode)
            view.scaleType = if (photo != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            view.clipToOutline = photo != null
            if (photo != null) {
                view.setImageBitmap(photo)
                view.imageTintList = null
                view.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                view.setPadding(0, 0, 0, 0)
            } else if (folder.iconKey.isEmpty() && folder.iconColor.isEmpty()) {
                view.setImageResource(com.mattintech.papercase.R.drawable.ic_folder)
                view.background = null
                view.imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSecondaryContainer)
                )
                view.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            } else {
                DocumentIcons.apply(view, DocumentIcons.iconRes(folder.iconKey, com.mattintech.papercase.R.drawable.ic_folder), folder.iconColor)
                view.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            }
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
            highlightIfExpired(b.root, doc)
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
                b.tvExpiration.text = if (expired) "EXPIRED ${doc.expirationDate}" else "Expires: ${doc.expirationDate}"
                b.tvExpiration.setTypeface(null, if (expired) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
            highlightIfExpired(b.root, doc)

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
            b.tvExpiryPill.text = if (status == Expiry.EXPIRED) "EXPIRED" else "Expires ${doc.expirationDate}"
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

    /** Expired documents get a red outline, so they stand out in both views. */
    private fun highlightIfExpired(card: com.google.android.material.card.MaterialCardView, doc: SecureDocument) {
        val d = card.resources.displayMetrics.density
        if (expiryStatus(doc) == Expiry.EXPIRED) {
            card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorError)
            card.strokeWidth = (2 * d).toInt()
        } else {
            card.strokeColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutlineVariant)
            card.strokeWidth = (1 * d).toInt()
        }
    }

    private enum class Expiry { NONE, OK, SOON, EXPIRED }



    /** Soon = inside the document's reminder window (or 30 days if it has no reminder). */
    private fun expiryStatus(doc: SecureDocument): Expiry {
        val date = ExpiryDates.parse(doc.expirationDate) ?: return Expiry.NONE
        val today = LocalDate.now()
        val window = if (doc.reminderDays > 0) doc.reminderDays.toLong() else 30L
        return when {
            isExpired(doc) -> Expiry.EXPIRED
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
            a is LockerItem.RowEnd && b is LockerItem.RowEnd -> true
            else -> false
        }

        override fun areContentsTheSame(a: LockerItem, b: LockerItem) = a == b
    }

    companion object {
        fun isExpired(doc: SecureDocument): Boolean =
            ExpiryDates.parse(doc.expirationDate)?.isBefore(LocalDate.now()) == true

        private const val TYPE_FOLDER = 0
        private const val TYPE_ROW = 1
        private const val TYPE_CARD = 2
        private const val TYPE_ROW_END = 3
    }
}
