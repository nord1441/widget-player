package com.example.shuffleplayer.playback

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Resolves a source URI (an m3u file, a folder tree URI, or a single media file)
 * into a list of [ResolvedTrack] ready to feed into the player.
 */
object SourceLoader {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma",
        "ape", "mka", "mid", "midi",
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts",
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

    enum class SourceKind { M3U, FOLDER, SINGLE_FILE, STREAM }

    fun classify(context: Context, uri: Uri): SourceKind {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            // .m3u8 over http is still a playlist as far as ExoPlayer is concerned,
            // but we let the player handle it as a stream rather than parsing here.
            return SourceKind.STREAM
        }
        val ext = extensionOf(context, uri)
        return when {
            ext in PLAYLIST_EXTENSIONS -> SourceKind.M3U
            isTreeUri(uri) -> SourceKind.FOLDER
            else -> SourceKind.SINGLE_FILE
        }
    }

    fun load(context: Context, uri: Uri): List<ResolvedTrack> {
        return when (classify(context, uri)) {
            SourceKind.M3U -> M3uParser.parse(context, uri)
            SourceKind.FOLDER -> loadFolder(context, uri)
            SourceKind.SINGLE_FILE -> listOf(
                ResolvedTrack(
                    uri = uri,
                    displayTitle = displayNameOf(context, uri),
                    artist = null,
                    durationSec = null,
                ),
            )
            SourceKind.STREAM -> listOf(
                ResolvedTrack(
                    uri = uri,
                    displayTitle = uri.lastPathSegment,
                    artist = null,
                    durationSec = null,
                ),
            )
        }
    }

    private fun loadFolder(context: Context, treeUri: Uri): List<ResolvedTrack> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<ResolvedTrack>()
        walk(root) { file ->
            val ext = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS) {
                out += ResolvedTrack(
                    uri = file.uri,
                    displayTitle = file.name?.substringBeforeLast('.'),
                    artist = null,
                    durationSec = null,
                )
            }
        }
        return out.sortedBy { it.displayTitle?.lowercase() ?: it.uri.toString() }
    }

    private fun walk(dir: DocumentFile, onFile: (DocumentFile) -> Unit) {
        dir.listFiles().forEach { child ->
            if (child.isDirectory) walk(child, onFile) else onFile(child)
        }
    }

    private fun isTreeUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return uri.scheme == "content" && path.startsWith("/tree")
    }

    private fun extensionOf(context: Context, uri: Uri): String? {
        val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: return null
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return name.substring(dot + 1).lowercase()
    }

    private fun displayNameOf(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        return doc?.name ?: uri.lastPathSegment
    }
}
