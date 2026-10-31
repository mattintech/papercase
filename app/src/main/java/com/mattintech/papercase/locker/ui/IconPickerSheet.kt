package com.mattintech.papercase.locker.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.locker.model.DocumentIcons
import com.mattintech.papercase.ui.SecureBottomSheetDialog

/**
 * Bottom sheet for choosing an icon and its colour. A blank icon key means [defaultIcon] (the
 * document type's icon, or the folder icon). [onChanged] runs on every tap; [onDone] once, on close.
 * With [onPhoto] (folders), it also offers a photo; picking a photo action closes the sheet and calls
 * [onPhoto] instead of [onDone].
 */
class IconPickerSheet(
    private val context: Context,
    private val defaultIcon: Int,
    private var iconKey: String,
    private var colourKey: String,
    private val onDone: ((iconKey: String, colourKey: String) -> Unit)? = null,
    private val hasPhoto: Boolean = false,
    private val onPhoto: ((PhotoAction) -> Unit)? = null,
    private val onChanged: (iconKey: String, colourKey: String) -> Unit = { _, _ -> }
) {
    enum class PhotoAction { TAKE, CHOOSE, REMOVE }

    private var photoAction: PhotoAction? = null
    private val dialog = SecureBottomSheetDialog(context)
    private val iconGrid = GridLayout(context).apply { columnCount = COLUMNS }
    private val colourRow = GridLayout(context).apply { columnCount = COLUMNS }

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(32))
            if (onPhoto != null) {
                addView(header("Photo"))
                addView(photoRow())
                addView(header("Icon").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20) })
            } else {
                addView(header("Icon"))
            }
            addView(iconGrid)
            addView(header("Colour").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20) })
            addView(colourRow)
        }
        render()
        dialog.setContentView(NestedScrollView(context).apply { addView(content) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener {
            val action = photoAction
            if (action != null) onPhoto?.invoke(action) else onDone?.invoke(iconKey, colourKey)
        }
        dialog.show()
    }

    private fun render() {
        // Blank key = follow the document type; show that as selected
        val selectedIcon = DocumentIcons.iconRes(iconKey, defaultIcon)
        iconGrid.removeAllViews()
        DocumentIcons.ICONS.forEach { icon ->
            iconGrid.addView(cell(selected = icon.res == selectedIcon, label = icon.label) { size ->
                ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    DocumentIcons.apply(this, icon.res, colourKey)
                }
            }.apply {
                setOnClickListener {
                    iconKey = icon.key
                    changed()
                }
            })
        }

        colourRow.removeAllViews()
        DocumentIcons.COLOURS.forEach { colour ->
            colourRow.addView(cell(selected = colour.key == colourKey, label = colour.label) { size ->
                ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(size - dp(8), size - dp(8), Gravity.CENTER)
                    val fill = if (colour.key.isEmpty()) {
                        MaterialColors.getColor(this@IconPickerSheet.iconGrid, androidx.appcompat.R.attr.colorPrimary)
                    } else colour.argb
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(fill)
                    }
                }
            }.apply {
                setOnClickListener {
                    colourKey = colour.key
                    changed()
                }
            })
        }
    }

    private fun photoRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val actions = listOf(PhotoAction.TAKE to "Camera", PhotoAction.CHOOSE to "Gallery") +
            if (hasPhoto) listOf(PhotoAction.REMOVE to "Remove") else emptyList()
        actions.forEachIndexed { i, (action, label) ->
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) marginStart = dp(8) }
                setOnClickListener {
                    photoAction = action
                    dialog.dismiss()
                }
            })
        }
    }

    private fun changed() {
        onChanged(iconKey, colourKey)
        render()
    }

    /** A square tappable cell; the selected one gets a ring. */
    private fun cell(selected: Boolean, label: String, child: (size: Int) -> View): FrameLayout {
        val size = dp(48)
        return FrameLayout(context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(52)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            contentDescription = label
            isClickable = true
            isFocusable = true
            background = if (selected) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), MaterialColors.getColor(iconGrid, com.google.android.material.R.attr.colorOnSurface))
                setColor(0)
            } else null
            addView(child(size))
        }
    }

    private fun header(text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 6
    }
}
