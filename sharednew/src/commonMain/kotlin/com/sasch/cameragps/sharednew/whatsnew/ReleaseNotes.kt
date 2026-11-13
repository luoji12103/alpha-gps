package com.sasch.cameragps.sharednew.whatsnew

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.whats_new_162_notifications
import cameragps.sharednew.generated.resources.whats_new_162_pairing
import cameragps.sharednew.generated.resources.whats_new_162_time
import cameragps.sharednew.generated.resources.whats_new_170_wifi_remote
import cameragps.sharednew.generated.resources.whats_new_171_rx0
import cameragps.sharednew.generated.resources.whats_new_171_zv1
import cameragps.sharednew.generated.resources.whats_new_power_1_location
import cameragps.sharednew.generated.resources.whats_new_power_1_nfc
import cameragps.sharednew.generated.resources.whats_new_power_1_scope
import org.jetbrains.compose.resources.StringResource

enum class ReleasePlatform { Android, Ios }

data class ReleaseNotes(val version: String, val highlights: List<StringResource>)

/** Add an entry and localized strings when shipping a release with user-facing changes. */
object ReleaseNotesCatalog {
    fun forVersion(version: String, platform: ReleasePlatform): ReleaseNotes? =
        when (version.removePrefix("v")) {
            "1.7.2+power.1" -> if (platform == ReleasePlatform.Android) ReleaseNotes(
                version = "1.7.2+power.1",
                highlights = listOf(
                    Res.string.whats_new_power_1_location,
                    Res.string.whats_new_power_1_nfc,
                    Res.string.whats_new_power_1_scope,
                ),
            ) else null

            "1.7.1" -> ReleaseNotes(
                version = "1.7.1",
                highlights = buildList {
                    add(Res.string.whats_new_171_zv1)
                    if (platform == ReleasePlatform.Android) add(Res.string.whats_new_171_rx0)
                },
            )

            "1.7.0" -> ReleaseNotes(
                version = "1.7.0",
                highlights = listOf(Res.string.whats_new_170_wifi_remote),
            )

            "1.6.2" -> ReleaseNotes(
                version = "1.6.2",
                highlights = buildList {
                    add(Res.string.whats_new_162_time)
                    if (platform == ReleasePlatform.Android) add(Res.string.whats_new_162_notifications)
                    if (platform == ReleasePlatform.Ios) add(Res.string.whats_new_162_pairing)
                },
            )

            else -> null
        }
}

object WhatsNewPolicy {
    // Compare the official numeric version first. For this fork's release-note
    // history only, compare power.N revisions at the same official version.
    // Android package updates are ordered separately by versionCode, not SemVer.
    fun isNewer(current: String, previous: String?): Boolean {
        fun parts(value: String): Pair<List<Int>, Int>? {
            val sections = value.removePrefix("v").split('+', limit = 2)
            val upstream = sections[0].split('.').map {
                it.toIntOrNull()?.takeIf { part -> part >= 0 } ?: return null
            }
            val revision = if (sections.size == 1) 0 else {
                val metadata = sections[1]
                if (!metadata.startsWith("power.")) return null
                metadata.removePrefix("power.").toIntOrNull()?.takeIf { it > 0 } ?: return null
            }
            return upstream to revision
        }

        val currentParts = parts(current) ?: return false
        val previousParts = previous?.let(::parts) ?: return true
        for (index in 0 until maxOf(currentParts.first.size, previousParts.first.size)) {
            val comparison = (currentParts.first.getOrNull(index) ?: 0)
                .compareTo(previousParts.first.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison > 0
        }
        return currentParts.second > previousParts.second
    }

    fun shouldShow(
        current: String,
        previous: String?,
        firstLaunch: Boolean,
        hasNotes: Boolean
    ): Boolean =
        !firstLaunch && hasNotes && isNewer(current, previous)
}
