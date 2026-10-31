package com.mattintech.papercase.onboarding

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.MainActivity
import com.mattintech.papercase.R
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.databinding.ActivityOnboardingBinding
import com.mattintech.papercase.databinding.ItemOnboardingPageBinding

/**
 * A few pages explaining what Papercase does, shown once on a new install (and from About).
 * On first run it opens the app when done; from About it just closes.
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Page(val art: Int, val title: String, val body: String)

    private val pages = listOf(
        Page(
            R.drawable.onboarding_welcome, "Your papers, safely cased",
            "Keep your passport, licence, health card and other documents on your phone, encrypted and ready when you need them."
        ),
        Page(
            R.drawable.onboarding_lock, "Opens only for you",
            "Unlock with your fingerprint or device PIN. Papercase locks itself when you leave, and screenshots are blocked."
        ),
        Page(
            R.drawable.onboarding_folders, "Organised for the whole family",
            "Make a folder for each person, give it a photo or an icon, and get reminded before anything expires."
        ),
        Page(
            R.drawable.onboarding_devices, "No cloud. No accounts.",
            "Nothing leaves your phone unless you send it. Move documents to your other device over Wi-Fi, or back up to a KeePass file."
        ),
        Page(
            R.drawable.onboarding_backup, "Keep a backup",
            "Your documents are tied to this phone's screen lock. If you remove it, they can't be opened again. Turn on automatic backups in Settings."
        )
    )

    private lateinit var binding: ActivityOnboardingBinding
    private val replay get() = intent.getBooleanExtra(EXTRA_REPLAY, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = PageAdapter()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render(position)
        })
        binding.btnSkip.setOnClickListener { finishOnboarding() }
        binding.btnBack.setOnClickListener { binding.pager.currentItem -= 1 }
        binding.btnNext.setOnClickListener {
            if (binding.pager.currentItem == pages.lastIndex) finishOnboarding() else binding.pager.currentItem += 1
        }

        // Back goes to the previous page first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.pager.currentItem > 0) binding.pager.currentItem -= 1 else finishOnboarding()
            }
        })

        render(binding.pager.currentItem)
    }

    private fun render(position: Int) {
        val last = position == pages.lastIndex
        binding.btnSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        binding.btnBack.visibility = if (position > 0) View.VISIBLE else View.GONE
        binding.btnNext.text = if (last) "Get started" else "Next"
        renderDots(position)
    }

    /** Page dots; the current one is a wider pill. */
    private fun renderDots(position: Int) {
        val d = resources.displayMetrics.density
        val on = MaterialColors.getColor(binding.dots, androidx.appcompat.R.attr.colorPrimary)
        val off = MaterialColors.getColor(binding.dots, com.google.android.material.R.attr.colorOutlineVariant)
        binding.dots.removeAllViews()
        binding.dots.contentDescription = "Page ${position + 1} of ${pages.size}"
        pages.indices.forEach { i ->
            binding.dots.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(((if (i == position) 24 else 8) * d).toInt(), (8 * d).toInt())
                    .apply { if (i > 0) marginStart = (8 * d).toInt() }
                background = GradientDrawable().apply {
                    cornerRadius = 4 * d
                    setColor(if (i == position) on else off)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    private fun finishOnboarding() {
        PreferencesManager(this).hasSeenOnboarding = true
        if (!replay) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(val b: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            holder.b.ivArt.setImageResource(page.art)
            holder.b.tvTitle.text = page.title
            holder.b.tvBody.text = page.body
        }
    }

    companion object {
        private const val EXTRA_REPLAY = "replay"

        /** Shows the pages again (from About); closing them returns to where the user was. */
        fun replay(context: Context) =
            context.startActivity(Intent(context, OnboardingActivity::class.java).putExtra(EXTRA_REPLAY, true))
    }
}
