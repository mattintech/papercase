package com.mattintech.papercase.locker.model

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.R

/** Icons and colours a document can be shown with, chosen when adding or editing it. */
object DocumentIcons {

    data class Icon(val key: String, val label: String, val res: Int)
    data class Colour(val key: String, val label: String, val argb: Int)

    val ICONS = listOf(
        Icon("face-baby", "Baby", R.drawable.ic_face_baby),
        Icon("face-boy", "Boy", R.drawable.ic_face_boy),
        Icon("face-girl", "Girl", R.drawable.ic_face_girl),
        Icon("face-bun", "Girl with bun", R.drawable.ic_face_bun),
        Icon("face-curly", "Curly hair", R.drawable.ic_face_curly),
        Icon("face-kid", "Child", R.drawable.ic_face_kid),
        Icon("id", "ID card", R.drawable.ic_badge),
        Icon("passport", "Passport", R.drawable.ic_shield_lock),
        Icon("travel", "Travel", R.drawable.ic_doc_travel),
        Icon("plane", "Plane", R.drawable.ic_doc_plane),
        Icon("car", "Car", R.drawable.ic_doc_car),
        Icon("motorcycle", "Motorcycle", R.drawable.ic_doc_motorcycle),
        Icon("boat", "Boat", R.drawable.ic_doc_boat),
        Icon("fishing", "Fishing", R.drawable.ic_doc_fishing),
        Icon("firearm", "Firearm", R.drawable.ic_doc_firearm),
        Icon("outdoors", "Outdoors", R.drawable.ic_doc_outdoors),
        Icon("medical", "Medical", R.drawable.ic_doc_medical),
        Icon("vaccine", "Vaccination", R.drawable.ic_doc_vaccine),
        Icon("insurance", "Insurance", R.drawable.ic_shield_check),
        Icon("pet", "Pet", R.drawable.ic_doc_pet),
        Icon("home", "Home", R.drawable.ic_doc_home),
        Icon("family", "Family", R.drawable.ic_doc_family),
        Icon("heart", "Heart", R.drawable.ic_doc_heart),
        Icon("school", "School", R.drawable.ic_doc_school),
        Icon("work", "Work", R.drawable.ic_doc_work),
        Icon("bank", "Bank", R.drawable.ic_doc_bank),
        Icon("card", "Card", R.drawable.ic_doc_card),
        Icon("receipt", "Receipt", R.drawable.ic_doc_receipt),
        Icon("document", "Document", R.drawable.ic_description),
        Icon("locker", "Locker", R.drawable.ic_folder_lock),
    )

    /** Mid tones that read on both light and dark backgrounds. Blank key = the app's accent colour. */
    val COLOURS = listOf(
        Colour("", "Default", 0),
        Colour("teal", "Teal", 0xFF00897B.toInt()),
        Colour("green", "Green", 0xFF43A047.toInt()),
        Colour("orange", "Orange", 0xFFF57C00.toInt()),
        Colour("red", "Red", 0xFFE53935.toInt()),
        Colour("pink", "Pink", 0xFFD81B60.toInt()),
        Colour("purple", "Purple", 0xFF8E24AA.toInt()),
        Colour("indigo", "Indigo", 0xFF3949AB.toInt()),
        Colour("brown", "Brown", 0xFF6D4C41.toInt()),
        Colour("grey", "Grey", 0xFF546E7A.toInt()),
    )

    fun iconRes(doc: SecureDocument): Int = iconRes(doc.iconKey, doc.type)

    fun iconRes(key: String, type: DocumentType): Int = iconRes(key, type.iconRes)

    fun iconRes(key: String, default: Int): Int = ICONS.firstOrNull { it.key == key }?.res ?: default

    /** Shows [iconRes] in [view] (which has a circle background) in the colour [colourKey]. */
    fun apply(view: ImageView, iconRes: Int, colourKey: String) {
        view.setImageResource(iconRes)
        val colour = COLOURS.firstOrNull { it.key == colourKey && it.key.isNotEmpty() }?.argb
        val tint = colour ?: MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        val background = if (colour != null) {
            ColorUtils.setAlphaComponent(colour, 0x33)
        } else {
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer)
        }
        view.imageTintList = ColorStateList.valueOf(tint)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(background)
        }
    }

    fun apply(view: ImageView, doc: SecureDocument) = apply(view, iconRes(doc), doc.iconColor)
}
