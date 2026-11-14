package com.example.data.identity

import com.example.data.local.EpisodeEntry

/** Backend episode records proven to represent the same anime/season/episode. */
object EpisodeSourceEpisodeIds {
    fun read(episode: EpisodeEntry): List<String> =
        (listOf(episode.id) + decode(episode.sourceEpisodeIds)).filter(String::isNotBlank).distinct()

    fun merge(vararg episodes: EpisodeEntry): String? {
        val ids = episodes.flatMap(::read).filter(String::isNotBlank).distinct().sorted()
        return encode(ids).takeIf { ids.size > 1 }
    }

    internal fun encode(ids: List<String>): String = buildString {
        ids.forEach { id -> append(id.length).append(':').append(id) }
    }

    internal fun decode(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val separator = value.indexOf(':', index)
            if (separator < 0) return emptyList()
            val length = value.substring(index, separator).toIntOrNull() ?: return emptyList()
            val start = separator + 1
            val end = start + length
            if (length < 0 || end > value.length) return emptyList()
            result += value.substring(start, end)
            index = end
        }
        return result
    }
}
