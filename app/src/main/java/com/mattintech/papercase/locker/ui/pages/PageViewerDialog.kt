package com.mattintech.papercase.locker.ui.pages

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mattintech.papercase.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page in the full-screen viewer: a photo, or a page of a PDF rendered when shown. */
sealed class PageSource(val title: String) {
    class Image(val bitmap: Bitmap, title: String) : PageSource(title)
    class PdfPage(val pdf: PdfPages, val index: Int, title: String) : PageSource(title)
}

/** Full-screen pages of a document: swipe between pages, pinch or double-tap to zoom. */
class PageViewerDialog(
    private val activity: AppCompatActivity,
    private val pages: List<PageSource>,
    private val startIndex: Int
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

    fun show() {
        // The activity's FLAG_SECURE doesn't cover dialog windows
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val pager = ViewPager2(activity).apply {
            adapter = Adapter()
            offscreenPageLimit = 1
        }
        val title = TextView(activity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_clear)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(8), dp(4), dp(8))
            addView(title)
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + dp(8))
            insets
        }

        fun updateTitle(position: Int) {
            val page = pages[position]
            title.text = when {
                pages.size == 1 -> page.title
                // Numbered photo pages: the position already says it
                page.title.startsWith("Page ") -> "Page ${position + 1} of ${pages.size}"
                else -> "${page.title}  ·  ${position + 1} of ${pages.size}"
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateTitle(position)
        })
        pager.setCurrentItem(startIndex, false)
        updateTitle(startIndex)

        dialog.setContentView(root)
        dialog.show()
    }

    private inner class Holder(val frame: FrameLayout, val image: ZoomableImageView, val progress: ProgressBar) :
        RecyclerView.ViewHolder(frame) {
        var job: Job? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ZoomableImageView(activity)
            val progress = ProgressBar(activity)
            val frame = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            }
            return Holder(frame, image, progress)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.job?.cancel()
            holder.image.setImageDrawable(null)
            when (val page = pages[position]) {
                is PageSource.Image -> {
                    holder.progress.visibility = View.GONE
                    holder.image.setImageBitmap(page.bitmap)
                }
                is PageSource.PdfPage -> {
                    holder.progress.visibility = View.VISIBLE
                    holder.job = activity.lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching { page.pdf.render(page.index, PDF_PAGE_EDGE) }.getOrNull()
                        }
                        holder.progress.visibility = View.GONE
                        if (bitmap != null) holder.image.setImageBitmap(bitmap)
                    }
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            // Rendered PDF pages are large; let them go as soon as they're off screen
            holder.job?.cancel()
            holder.image.setImageDrawable(null)
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        /** Sharp enough to read small print when zoomed, without holding too much memory. */
        const val PDF_PAGE_EDGE = 2600
    }
}
