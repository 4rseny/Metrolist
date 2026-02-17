/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Helper class for saving videos to MediaStore.
 * Videos are saved to Movies/Metrolist/{Artist}/ folder.
 */
class MediaStoreHelper(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreHelper"
        private const val METROLIST_FOLDER = "Metrolist"
    }

    /**
     * Saves a video file to MediaStore.
     *
     * @param tempFile The temporary video file to save
     * @param fileName The desired file name (e.g., "Song Name (1080p).mp4")
     * @param mimeType The MIME type (e.g., "video/mp4")
     * @param title The video title
     * @param artist The artist name
     * @param durationMs The video duration in milliseconds
     * @return The MediaStore URI if successful, null otherwise
     */
    suspend fun saveVideoToMediaStore(
        tempFile: File,
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        durationMs: Long? = null
    ): Uri? = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("saveVideoToMediaStore: fileName=$fileName, mimeType=$mimeType, size=${tempFile.length()}")

        try {
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Timber.tag(TAG).e("Temp file doesn't exist or is empty")
                return@withContext null
            }

            tempFile.inputStream().use { inputStream ->
                saveVideoToMediaStoreInternal(
                    inputStream = inputStream,
                    fileName = sanitizeFileName(fileName),
                    mimeType = mimeType,
                    title = title,
                    artist = artist,
                    durationMs = durationMs
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "saveVideoToMediaStore failed: ${e.message}")
            null
        }
    }

    private suspend fun saveVideoToMediaStoreInternal(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        durationMs: Long? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // Videos go to Movies/Metrolist/{Artist}/ folder
            val relativePath = "${Environment.DIRECTORY_MOVIES}/$METROLIST_FOLDER/${sanitizeFolderName(artist)}"

            // Check if file already exists and delete it to prevent duplicates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findVideoByPath(relativePath, fileName)?.let { existingUri ->
                    contentResolver.delete(existingUri, null, null)
                    Timber.tag(TAG).d("Deleted existing file: $existingUri")
                }
            }

            // Prepare ContentValues with metadata
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.TITLE, title)
                put(MediaStore.Video.Media.ARTIST, artist)
                durationMs?.let { put(MediaStore.Video.Media.DURATION, it) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    // Legacy path for older Android versions
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "$METROLIST_FOLDER/${sanitizeFolderName(artist)}"
                    )
                    targetDir.mkdirs()
                    val targetFile = File(targetDir, fileName)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
                }
            }

            // Insert the file entry into MediaStore
            val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val videoUri = contentResolver.insert(videoCollection, contentValues)

            if (videoUri == null) {
                Timber.tag(TAG).e("Failed to create MediaStore entry")
                return@withContext null
            }

            // Write the actual file content
            contentResolver.openOutputStream(videoUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
                outputStream.flush()
            } ?: run {
                contentResolver.delete(videoUri, null, null)
                Timber.tag(TAG).e("Failed to open output stream")
                return@withContext null
            }

            // Mark file as ready (remove IS_PENDING flag)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(videoUri, contentValues, null, null)
            }

            Timber.tag(TAG).d("Video saved successfully: $videoUri")
            videoUri

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "saveVideoToMediaStoreInternal failed: ${e.message}")
            null
        }
    }

    private fun findVideoByPath(relativePath: String, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("$relativePath/", fileName)

        return context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                Uri.withAppendedPath(collection, id.toString())
            } else {
                null
            }
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200) // Limit length
    }

    private fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
            .ifBlank { "Unknown Artist" }
    }

    /**
     * Deletes a video from MediaStore and cleans up empty parent folders.
     *
     * @param uri The MediaStore URI of the video to delete
     * @return true if deletion was successful
     */
    suspend fun deleteVideoFromMediaStore(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // Get the relative path before deleting to know which folder to clean up
            var relativePath: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Video.Media.RELATIVE_PATH)
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH))
                    }
                }
            }

            // Delete the video file
            val rowsDeleted = contentResolver.delete(uri, null, null)
            if (rowsDeleted <= 0) {
                Timber.tag(TAG).e("Failed to delete video: $uri")
                return@withContext false
            }

            Timber.tag(TAG).d("Deleted video: $uri")

            // Clean up empty folders
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                cleanupEmptyFolders(relativePath)
            } else {
                // For legacy Android, clean up using direct file access
                cleanupEmptyFoldersLegacy()
            }

            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "deleteVideoFromMediaStore failed: ${e.message}")
            false
        }
    }

    /**
     * Clean up empty folders in Movies/Metrolist/ on Android Q+
     */
    private fun cleanupEmptyFolders(relativePath: String) {
        try {
            // relativePath is like "Movies/Metrolist/Artist/"
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val metrolistDir = File(moviesDir, METROLIST_FOLDER)

            // Get the artist folder name from relativePath
            // relativePath format: Movies/Metrolist/ArtistName/
            val parts = relativePath.trimEnd('/').split("/")
            if (parts.size >= 3) {
                val artistFolderName = parts[2]
                val artistDir = File(metrolistDir, artistFolderName)

                // Delete artist folder if empty
                if (artistDir.exists() && artistDir.isDirectory && artistDir.listFiles()?.isEmpty() == true) {
                    if (artistDir.delete()) {
                        Timber.tag(TAG).d("Deleted empty artist folder: ${artistDir.absolutePath}")
                    }
                }
            }

            // Delete Metrolist folder if empty
            if (metrolistDir.exists() && metrolistDir.isDirectory && metrolistDir.listFiles()?.isEmpty() == true) {
                if (metrolistDir.delete()) {
                    Timber.tag(TAG).d("Deleted empty Metrolist folder: ${metrolistDir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "cleanupEmptyFolders failed: ${e.message}")
        }
    }

    /**
     * Clean up empty folders for legacy Android versions
     */
    private fun cleanupEmptyFoldersLegacy() {
        try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val metrolistDir = File(moviesDir, METROLIST_FOLDER)

            if (metrolistDir.exists() && metrolistDir.isDirectory) {
                // Delete any empty artist folders
                metrolistDir.listFiles()?.forEach { artistDir ->
                    if (artistDir.isDirectory && artistDir.listFiles()?.isEmpty() == true) {
                        if (artistDir.delete()) {
                            Timber.tag(TAG).d("Deleted empty artist folder: ${artistDir.absolutePath}")
                        }
                    }
                }

                // Delete Metrolist folder if empty
                if (metrolistDir.listFiles()?.isEmpty() == true) {
                    if (metrolistDir.delete()) {
                        Timber.tag(TAG).d("Deleted empty Metrolist folder: ${metrolistDir.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "cleanupEmptyFoldersLegacy failed: ${e.message}")
        }
    }
}
