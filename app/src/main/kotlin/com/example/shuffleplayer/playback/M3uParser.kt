package com.example.shuffleplayer.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Single parsed m3u entry — the textual target plus optional EXTINF metadata.
 * Resolution to a concrete Uri happens later via [M3uParser.resolveTarget].
 */
data class M3uEntry(
    val rawTarget: String,
    val durationSec: Int? = null,
    val artist: String? = null,
    val title: String? = null,
)

data class ResolvedTrack(
    val uri: Uri,
    val displayTitle: String?,
    val artist: String?,
    val durationSec: Int?,
)

object M3uParser {

    private const val BOM = '﻿'

    /**
     * Pure-text parsing. No I/O, no Android deps. Suitable for unit tests.
     */
    fun parseText(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingDuration: Int? = null
        var pendingArtist: String? = null
        var pendingTitle: String? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = if (index == 0) rawLine.trimStart(BOM).trim() else rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val payload = line.substring("#EXTINF:".length)
                    val comma = payload.indexOf(',')
                    val durationStr = if (comma >= 0) payload.substring(0, comma) else payload
                    val rest = if (comma >= 0) payload.substring(comma + 1).trim() else ""
                    pendingDuration = parseDurationSec(durationStr)
                    val (a, t) = splitArtistTitle(rest)
                    pendingArtist = a
                    pendingTitle = t
                }
                line.startsWith("#") -> Unit // other directives / comments
                else -> {
                    entries += M3uEntry(
                        rawTarget = line,
                        durationSec = pendingDuration,
                        artist = pendingArtist,
                        title = pendingTitle,
                    )
                    pendingDuration = null
                    pendingArtist = null
                    pendingTitle = null
                }
            }
        }
        return entries
    }

    /**
     * Read [m3uUri] (content:// or file://), parse it, and resolve every entry.
     * Entries that can't be resolved (e.g. relative path with no accessible parent)
     * are dropped — caller can inspect what was filtered if needed.
     */
    fun parse(context: Context, m3uUri: Uri): List<ResolvedTrack> {
        val text = readText(context.contentResolver, m3uUri) ?: return emptyList()
        val entries = parseText(text)
        return entries.mapNotNull { entry ->
            val uri = resolveTarget(context, m3uUri, entry.rawTarget) ?: return@mapNotNull null
            ResolvedTrack(
                uri = uri,
                displayTitle = entry.title ?: defaultTitleFromUri(uri),
                artist = entry.artist,
                durationSec = entry.durationSec,
            )
        }
    }

    /**
     * Classify a raw line and turn it into a concrete Uri.
     *
     * - http/https/content schemes pass through.
     * - Absolute filesystem path → file:// Uri.
     * - Relative path → resolve against the parent of [m3uUri].
     */
    fun resolveTarget(context: Context, m3uUri: Uri, rawTarget: String): Uri? {
        val target = rawTarget.trim()
        if (target.isEmpty()) return null

        // Already a URI with a scheme we understand.
        val parsed = runCatching { Uri.parse(target) }.getOrNull()
        if (parsed != null && parsed.scheme != null) {
            when (parsed.scheme!!.lowercase()) {
                "http", "https", "content" -> return parsed
                "file" -> return parsed
            }
        }

        // Absolute filesystem path.
        if (target.startsWith("/")) return Uri.fromFile(File(target))

        // Relative — resolve against m3u parent.
        return resolveRelative(context, m3uUri, target)
    }

    private fun resolveRelative(context: Context, m3uUri: Uri, relative: String): Uri? {
        return when (m3uUri.scheme?.lowercase()) {
            "file", null -> {
                val parent = m3uUri.path?.let { File(it).parentFile } ?: return null
                Uri.fromFile(File(parent, relative))
            }
            "content" -> {
                // Try DocumentFile parent traversal. Only works when the URI was
                // opened as part of a tree the app holds permission for.
                val doc = DocumentFile.fromSingleUri(context, m3uUri) ?: return null
                val parent = doc.parentFile ?: return null
                walkRelative(parent, relative.split('/'))?.uri
            }
            else -> null
        }
    }

    private tailrec fun walkRelative(dir: DocumentFile, parts: List<String>): DocumentFile? {
        if (parts.isEmpty()) return dir
        val head = parts.first()
        val rest = parts.drop(1)
        val next = when (head) {
            "", "." -> dir
            ".." -> dir.parentFile ?: return null
            else -> dir.findFile(head) ?: return null
        }
        return walkRelative(next, rest)
    }

    private fun readText(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.openInputStream(uri)?.use { stream -> readWithCharset(stream) }
        }.getOrNull()
    }

    private fun readWithCharset(stream: InputStream): String {
        val bytes = stream.readBytes()
        // UTF-8 is the m3u default; Charset.defaultCharset() is unreliable across devices.
        return String(bytes, Charset.forName("UTF-8"))
    }

    private fun parseDurationSec(s: String): Int? {
        // EXTINF duration may be a float; we round to int seconds. -1 means unknown.
        val v = s.trim().toDoubleOrNull() ?: return null
        if (v < 0) return null
        return v.toInt()
    }

    private fun splitArtistTitle(s: String): Pair<String?, String?> {
        if (s.isBlank()) return null to null
        val sep = s.indexOf(" - ")
        return if (sep > 0) {
            s.substring(0, sep).trim().ifEmpty { null } to
                s.substring(sep + 3).trim().ifEmpty { null }
        } else {
            null to s.trim()
        }
    }

    private fun defaultTitleFromUri(uri: Uri): String? {
        val last = uri.lastPathSegment ?: return null
        return last.substringAfterLast('/').substringBeforeLast('.').ifEmpty { null }
    }
}
