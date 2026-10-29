package com.mattintech.securepass.locker.data

import android.content.Context
import com.mattintech.securepass.data.PreferencesManager
import java.util.Locale

object IssuingAuthorityHelper {

    private val COMMON_AUTHORITIES = listOf(
        "Passport Canada",
        "ServiceOntario",
        "ICBC (British Columbia)",
        "SAAQ (Quebec)",
        "Alberta Registry",
        "U.S. Department of State",
        "California DMV",
        "New York DMV",
        "Texas DPS",
        "Florida DHSMV",
        "Washington DOL",
        "HM Passport Office (UK)",
        "DVLA (UK)",
        "UK Home Office",
        "Australian Passport Office",
        "Service NSW (Australia)",
        "VicRoads (Australia)"
    )

    fun getBaseAuthorities(): List<String> {
        val isoCountries = Locale.getISOCountries().mapNotNull { code ->
            try {
                val country = Locale.Builder().setRegion(code).build().displayCountry
                if (country.isNotBlank()) country else null
            } catch (_: Exception) {
                null
            }
        }
        val all = LinkedHashSet<String>()
        all.addAll(COMMON_AUTHORITIES)
        all.addAll(isoCountries.sorted())
        return all.toList()
    }

    fun getLookupList(context: Context): List<String> {
        val prefs = PreferencesManager(context)
        val customAuthorities = prefs.getCustomAuthorities()

        val all = LinkedHashSet<String>()

        // 1. User's custom recorded authorities first
        all.addAll(customAuthorities.sorted())

        // 2. Base common authorities & ISO countries
        all.addAll(getBaseAuthorities())

        return all.toList()
    }

    fun recordNewAuthority(context: Context, entered: String) {
        val trimmed = entered.trim()
        if (trimmed.length < 2) return

        val existing = getLookupList(context)
        val alreadyExists = existing.any { it.equals(trimmed, ignoreCase = true) }
        if (!alreadyExists) {
            PreferencesManager(context).addCustomAuthority(trimmed)
        }
    }
}
