package com.socatv.nova.utils

import com.socatv.nova.data.model.Channel
import java.net.URL
import java.util.UUID

object M3uParser {

    data class Result(
        val channels: List<Channel>,
        val error: String? = null
    )

    fun parseFromUrl(m3uUrl: String): Result {
        return try {
            val text = URL(m3uUrl).openStream().bufferedReader().readText()
            parseText(text)
        } catch (e: Exception) {
            Result(emptyList(), "Failed to fetch playlist: ${e.message}")
        }
    }

    fun parseText(text: String): Result {
        if (!text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            return Result(emptyList(), "Not a valid M3U playlist")
        }

        val channels = mutableListOf<Channel>()
        val lines = text.lines()
        var i = 0
        var num = 1

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info    = line.removePrefix("#EXTINF:").removePrefix("#EXTINF:")
                val name    = extractName(info)
                val logo    = extractAttr(info, "tvg-logo")
                val groupId = extractAttr(info, "group-title") ?: "M3U Imported"
                val epgId   = extractAttr(info, "tvg-id")

                // Find the next non-comment, non-empty line as the URL
                var url: String? = null
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        url = next; break
                    }
                    j++
                }

                if (url != null && name.isNotBlank()) {
                    val streamId = "m3u_${UUID.nameUUIDFromBytes("$name$url".toByteArray())}"
                    channels.add(Channel(
                        streamId         = streamId,
                        name             = name,
                        streamIcon       = logo,
                        epgChannelId     = epgId,
                        added            = null,
                        categoryId       = groupId,
                        customSid        = null,
                        tvArchive        = 0,
                        directSource     = url,
                        tvArchiveDuration = 0,
                        num              = num++
                    ))
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return Result(channels)
    }

    private fun extractName(line: String): String {
        val commaIdx = line.lastIndexOf(',')
        return if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
    }

    private fun extractAttr(line: String, attr: String): String? {
        // Match attr="value" or attr='value'
        val patterns = listOf("""$attr="([^"]*)" """, """$attr='([^']*)' """,
                               """$attr="([^"]*)"""",   """$attr='([^']*)' """)
        val regex = Regex("""$attr=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
