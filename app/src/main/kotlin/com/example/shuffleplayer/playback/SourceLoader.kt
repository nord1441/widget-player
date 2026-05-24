package com.example.shuffleplayer.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Resolves a source URI (an m3u file, a folder tree URI, or a single media file)
 * into a list of [ResolvedTrack] ready to feed into the player.
 */
object SourceLoader {

    private const val TAG = "SourceLoader"

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

    /**
     * Iterative BFS using [DocumentsContract] directly (one query per folder vs the
     * N+1 pattern of [DocumentFile.listFiles] + per-child [DocumentFile.isDirectory]).
     * Per-folder failures are logged and skipped so a single inaccessible subtree
     * does not abort the whole scan.
     */
    private fun loadFolder(context: Context, treeUri: Uri): List<ResolvedTrack> {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return emptyList()

        val out = mutableListOf<ResolvedTrack>()
        val queue = ArrayDeque<String>().apply { addLast(rootId) }
        val visited = HashSet<String>()

        while (queue.isNotEmpty()) {
            val parentId = queue.removeFirst()
            if (!visited.add(parentId)) continue
            scanChildren(resolver, treeUri, parentId, queue, out)
        }
        return out.sortedBy { it.displayTitle?.lowercase() ?: it.uri.toString() }
    }

    private fun scanChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        queue: ArrayDeque<String>,
        out: MutableList<ResolvedTrack>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1)
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.addLast(id)
                    } else if (name != null && isMediaFile(name)) {
                        out += ResolvedTrack(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            displayTitle = name.substringBeforeLast('.', name),
                            artist = null,
                            durationSec = null,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            // Misbehaving providers, revoked permissions on a subtree, etc.
            // Skip this folder and keep walking the rest.
            Log.w(TAG, "Failed to list children of $parentId in $treeUri", t)
        }
    }

    private fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && (ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS)
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
