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
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.locker.model.DocumentIcons
import com.mattintech.papercase.ui.SecureBottomSheetDialog

/**
 * Bottom sheet for choosing an icon and its colour. A blank icon key means [defaultIcon] (the
 * document type's icon, or the folder icon). [onChanged] runs on every tap; [onDone] once, on close.
 */
class IconPickerSheet(
    private val context: Context,
    private val defaultIcon: Int,
    private var iconKey: String,
    private var colourKey: String,
    private val onDone: ((iconKey: String, colourKey: String) -> Unit)? = null,
    private val onChanged: (iconKey: String, colourKey: String) -> Unit = { _, _ -> }
) {
    private val dialog = SecureBottomSheetDialog(context)
    private val iconGrid = GridLayout(context).apply { columnCount = COLUMNS }
    private val colourRow = GridLayout(context).apply { columnCount = COLUMNS }

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(32))
            addView(header("Icon"))
            addView(iconGrid)
            addView(header("Colour").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20) })
            addView(colourRow)
        }
        render()
        dialog.setContentView(NestedScrollView(context).apply { addView(content) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { onDone?.invoke(iconKey, colourKey) }
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
