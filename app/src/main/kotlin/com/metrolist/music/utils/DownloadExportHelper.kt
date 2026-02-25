/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.di.DownloadCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadExportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @DownloadCache private val downloadCache: SimpleCache,
    private val coverArtEmbedder: CoverArtEmbedder,
) {
    companion object {
        private const val TAG = "DownloadExportHelper"
    }

    /**
     * Export a downloaded song from the internal cache to a custom SAF directory.
     * Returns the URI of the exported file, or null if export failed.
     */
    suspend fun exportToCustomPath(
        songId: String,
        customPathUri: String
    ): String? = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== Starting export for song: $songId ===")
        Timber.tag(TAG).d("Custom path URI: $customPathUri")

        try {
            Timber.tag(TAG).d("Fetching song from database...")
            val song = database.song(songId).first() ?: run {
                Timber.tag(TAG).w("Song not found in database: $songId")
                return@withContext null
            }
            Timber.tag(TAG).d("Song found: ${song.song.title}")

            Timber.tag(TAG).d("Fetching format info...")
            val format = database.format(songId).first()
            val extension = getExtensionFromFormat(format)
            Timber.tag(TAG).d("Format: ${format?.mimeType ?: "unknown"}, Extension: $extension")

            // Build folder structure: downloadFolder/Artist/Title.ext
            val firstArtist = song.artists.firstOrNull()?.name ?: "Unknown Artist"
            val allArtists = song.artists.joinToString(", ") { it.name }
                .ifEmpty { "Unknown Artist" }
            val title = song.song.title
            val sanitizedArtistFolder = sanitizeFilename(firstArtist)
            val sanitizedFilename = sanitizeFilename("$title.$extension")
            Timber.tag(TAG).d("Artist folder: $sanitizedArtistFolder, Filename: $sanitizedFilename")

            Timber.tag(TAG).d("Parsing parent URI...")
            val parentUri = Uri.parse(customPathUri)
            val rootDoc = DocumentFile.fromTreeUri(context, parentUri) ?: run {
                Timber.tag(TAG).e("Cannot access custom path: $customPathUri")
                return@withContext null
            }
            Timber.tag(TAG).d("Root document: ${rootDoc.name}, canRead: ${rootDoc.canRead()}, canWrite: ${rootDoc.canWrite()}")

            if (!rootDoc.canWrite()) {
                Timber.tag(TAG).e("Cannot write to custom path: $customPathUri")
                return@withContext null
            }

            // Create or get artist subfolder
            var artistFolder = rootDoc.findFile(sanitizedArtistFolder)
            if (artistFolder == null || !artistFolder.isDirectory) {
                Timber.tag(TAG).d("Creating artist folder: $sanitizedArtistFolder")
                artistFolder = rootDoc.createDirectory(sanitizedArtistFolder)
                if (artistFolder == null) {
                    Timber.tag(TAG).e("Failed to create artist folder: $sanitizedArtistFolder")
                    return@withContext null
                }
            }
            Timber.tag(TAG).d("Artist folder ready: ${artistFolder.uri}")

            // Check if file already exists in artist folder and delete it
            val existingFile = artistFolder.findFile(sanitizedFilename)
            if (existingFile != null) {
                Timber.tag(TAG).d("Existing file found, deleting...")
                existingFile.delete()
            }

            // Create the new file in artist folder
            val mimeType = format?.mimeType ?: "audio/mp4"
            Timber.tag(TAG).d("Creating new file with mimeType: $mimeType")
            val newFile = artistFolder.createFile(mimeType, sanitizedFilename) ?: run {
                Timber.tag(TAG).e("Failed to create file: $sanitizedFilename")
                return@withContext null
            }
            Timber.tag(TAG).d("Created file: ${newFile.uri}")

            // Copy data from cache to new file
            Timber.tag(TAG).d("Opening output stream...")
            val outputStream = context.contentResolver.openOutputStream(newFile.uri) ?: run {
                Timber.tag(TAG).e("Failed to open output stream for: ${newFile.uri}")
                newFile.delete()
                return@withContext null
            }

            var totalBytesWritten = 0L
            outputStream.use { out ->
                Timber.tag(TAG).d("Getting cached spans for: $songId")
                val cachedSpans = downloadCache.getCachedSpans(songId)
                Timber.tag(TAG).d("Found ${cachedSpans.size} cached spans")

                if (cachedSpans.isEmpty()) {
                    Timber.tag(TAG).w("No cached data found for: $songId")
                    newFile.delete()
                    return@withContext null
                }

                // Sort spans by position and write them in order
                val sortedSpans = cachedSpans.sortedBy { it.position }
                Timber.tag(TAG).d("Spans sorted by position, starting copy...")

                for ((index, span) in sortedSpans.withIndex()) {
                    Timber.tag(TAG).d("Processing span $index: position=${span.position}, length=${span.length}, file=${span.file?.name}")
                    span.file?.inputStream()?.use { input ->
                        val bytes = input.copyTo(out)
                        totalBytesWritten += bytes
                        Timber.tag(TAG).d("Copied $bytes bytes from span $index")
                    }
                }
            }

            Timber.tag(TAG).d("Total bytes written: $totalBytesWritten")

            val exportedUri = newFile.uri.toString()

            // Embed metadata for M4A files (128kbps+ only - lower bitrates have compatibility issues)
            val bitrateKbps = (format?.bitrate ?: 0) / 1000
            if (format == null) {
                Timber.tag(TAG).w("Format entity is null for $songId - metadata embedding skipped")
            }
            Timber.tag(TAG).d("Metadata check: extension=$extension, bitrate=${bitrateKbps}kbps, eligible=${extension == "m4a" && bitrateKbps >= 128}")
            if (extension == "m4a" && bitrateKbps >= 128) {
                Timber.tag(TAG).d("M4A file detected (${bitrateKbps}kbps), embedding metadata...")
                try {
                    val artworkData = fetchArtworkData(song.song.thumbnailUrl)

                    // Try album relationship first, fall back to song entity fields
                    var albumName = song.album?.title ?: song.song.albumName
                    var year = (song.album?.year ?: song.song.year)?.toString()
                    var albumArtist: String? = null
                    var trackNumber = 0
                    var totalTracks = 0

                    // If missing album or year info and we have albumId, try fetching from YouTube
                    if ((albumName == null || year == null) && song.song.albumId != null) {
                        Timber.tag(TAG).d("Album/year info incomplete (album=$albumName, year=$year), fetching from YouTube...")
                        try {
                            val albumPage = com.metrolist.innertube.YouTube.album(song.song.albumId!!).getOrNull()
                            if (albumPage != null) {
                                if (albumName == null) albumName = albumPage.album.title
                                if (year == null) year = albumPage.album.year?.toString()
                                // Get album artist (first artist of album)
                                albumArtist = albumPage.album.artists?.firstOrNull()?.name
                                // Find track position in album
                                totalTracks = albumPage.songs.size
                                val trackIndex = albumPage.songs.indexOfFirst { it.id == songId }
                                if (trackIndex >= 0) {
                                    trackNumber = trackIndex + 1
                                }
                                Timber.tag(TAG).d("Fetched from YouTube - album: $albumName, year: $year, albumArtist: $albumArtist, track: $trackNumber/$totalTracks")
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Failed to fetch album info from YouTube")
                        }
                    }

                    val embedSuccess = coverArtEmbedder.embedMetadataIntoFile(
                        fileUri = newFile.uri,
                        artworkData = artworkData,
                        title = song.song.title,
                        artist = allArtists,
                        album = albumName,
                        year = year,
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        totalTracks = totalTracks
                    )

                    if (embedSuccess) {
                        Timber.tag(TAG).d("Metadata embedded successfully")
                    } else {
                        Timber.tag(TAG).w("Metadata embedding failed, file still exported without metadata")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error embedding metadata, continuing without metadata")
                }
            } else if (extension == "m4a") {
                Timber.tag(TAG).d("M4A file (${bitrateKbps}kbps) - skipping metadata embed (low bitrate not supported)")
            }

            // Update database with the new URI
            Timber.tag(TAG).d("Updating database with downloadUri...")
            database.updateDownloadUri(songId, exportedUri)

            Timber.tag(TAG).d("=== Successfully exported $songId to $exportedUri ===")
            exportedUri
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "IO error exporting song: $songId")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error exporting song: $songId")
            null
        }
    }

    /**
     * Delete a song from the custom path and clear the downloadUri in the database.
     */
    suspend fun deleteFromCustomPath(songId: String): Boolean = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== Starting delete for song: $songId ===")

        try {
            Timber.tag(TAG).d("Fetching downloadUri from database...")
            val downloadUri = database.getDownloadUri(songId)
            Timber.tag(TAG).d("downloadUri: $downloadUri")

            if (downloadUri.isNullOrEmpty()) {
                Timber.tag(TAG).d("No external file URI stored, nothing to delete")
                return@withContext true // Nothing to delete
            }

            Timber.tag(TAG).d("Parsing URI and getting DocumentFile...")
            val uri = Uri.parse(downloadUri)
            val docFile = DocumentFile.fromSingleUri(context, uri)
            Timber.tag(TAG).d("DocumentFile exists: ${docFile?.exists()}, name: ${docFile?.name}")

            val deleted = docFile?.delete() ?: false
            Timber.tag(TAG).d("Delete operation result: $deleted")

            if (deleted || docFile?.exists() == false) {
                Timber.tag(TAG).d("Clearing downloadUri in database...")
                database.updateDownloadUri(songId, null)
                Timber.tag(TAG).d("=== Successfully deleted external file for: $songId ===")
                return@withContext true
            }

            Timber.tag(TAG).w("Failed to delete external file for: $songId")
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error deleting external file for: $songId")
            false
        }
    }

    /**
     * Check if the given URI is still accessible with persisted permissions.
     */
    fun verifyPathAccess(uri: String): Boolean {
        Timber.tag(TAG).d("Verifying path access for: $uri")
        return try {
            val parsedUri = Uri.parse(uri)
            val docFile = DocumentFile.fromTreeUri(context, parsedUri)
            val canRead = docFile?.canRead() == true
            val canWrite = docFile?.canWrite() == true
            Timber.tag(TAG).d("Path access result - canRead: $canRead, canWrite: $canWrite")
            canRead && canWrite
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error verifying path access: $uri")
            false
        }
    }

    /**
     * Check if a specific downloaded file still exists and is accessible.
     */
    fun verifyFileAccess(uri: String): Boolean {
        Timber.tag(TAG).d("Verifying file access for: $uri")
        return try {
            val parsedUri = Uri.parse(uri)
            val docFile = DocumentFile.fromSingleUri(context, parsedUri)
            val exists = docFile?.exists() == true
            val canRead = docFile?.canRead() == true
            Timber.tag(TAG).d("File access result - exists: $exists, canRead: $canRead")
            exists && canRead
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error verifying file access: $uri")
            false
        }
    }

    private fun getExtensionFromFormat(format: FormatEntity?): String {
        return when {
            format == null -> "m4a"
            format.mimeType.contains("audio/webm") -> "ogg"
            format.mimeType.contains("audio/mp4") -> "m4a"
            format.mimeType.contains("audio/mpeg") -> "mp3"
            format.mimeType.contains("audio/ogg") -> "ogg"
            else -> "m4a"
        }
    }

    private fun sanitizeFilename(filename: String): String {
        // Remove or replace characters that are invalid in filenames
        return filename
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200) // Limit filename length
    }

    /**
     * Fetch artwork from URL and convert to JPEG byte array for embedding.
     */
    private suspend fun fetchArtworkData(thumbnailUrl: String?): ByteArray? {
        if (thumbnailUrl.isNullOrEmpty()) {
            Timber.tag(TAG).d("No thumbnail URL provided")
            return null
        }

        Timber.tag(TAG).d("Fetching artwork from: $thumbnailUrl")

        return try {
            val imageLoader = ImageLoader.Builder(context).build()
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .build()

            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val data = outputStream.toByteArray()
                Timber.tag(TAG).d("Artwork fetched successfully: ${data.size} bytes")
                data
            } else {
                Timber.tag(TAG).w("Failed to load artwork image")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching artwork")
            null
        }
    }
}
