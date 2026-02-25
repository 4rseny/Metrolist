/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.CustomDownloadPathEnabledKey
import com.metrolist.music.constants.CustomDownloadPathUriKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.utils.DownloadExportHelper
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.booleanPreference
import com.metrolist.music.utils.enumPreference
import com.metrolist.music.utils.stringPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
    private val downloadExportHelper: DownloadExportHelper,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val customDownloadPathEnabled by booleanPreference(context, CustomDownloadPathEnabledKey, false)
    private val customDownloadPathUri by stringPreference(context, CustomDownloadPathUriKey, "")
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    // Map of songId -> itag for user-selected format downloads
    private val targetItagOverride = mutableMapOf<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set the target itag for a specific song download.
     * This will be used instead of auto-selection when downloading.
     */
    fun setTargetItag(songId: String, itag: Int) {
        timber.log.Timber.tag("DownloadUtil").d("Setting target itag for $songId: $itag")
        targetItagOverride[songId] = itag
        // Invalidate cached URL to force fresh fetch with new format
        invalidateUrl(songId)
    }

    /**
     * Clear the target itag for a song (revert to auto-selection).
     */
    fun clearTargetItag(songId: String) {
        timber.log.Timber.tag("DownloadUtil").d("Clearing target itag for $songId")
        targetItagOverride.remove(songId)
    }

    /**
     * Invalidate cached URL for a specific song.
     * Call this when the stream URL needs to be refreshed (e.g., format change).
     */
    fun invalidateUrl(songId: String) {
        val hadEntry = songUrlCache.containsKey(songId)
        songUrlCache.remove(songId)
        if (hadEntry) {
            timber.log.Timber.tag("DownloadUtil").d("Invalidated cached URL for: $songId")
        }
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            // Check if user selected a specific format for this download
            // IMPORTANT: Check targetItag FIRST - if set, we must bypass cache to get that specific format
            val targetItag = targetItagOverride[mediaId] ?: 0
            val hasTargetItag = targetItag > 0
            timber.log.Timber.tag("DownloadUtil").d("Download resolver for $mediaId, targetItag=${if (hasTargetItag) targetItag else "auto"}")

            // Only use cache if no specific format was requested
            if (!hasTargetItag) {
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    timber.log.Timber.tag("DownloadUtil").d("Using player cache for $mediaId")
                    return@Factory dataSpec
                }

                // Fixed: use > for "not expired" (was < which meant "use if expired")
                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    timber.log.Timber.tag("DownloadUtil").d("Using URL cache for $mediaId")
                    return@Factory dataSpec.withUri(it.first.toUri())
                }
            } else {
                // Clear caches when specific format requested to ensure fresh fetch
                timber.log.Timber.tag("DownloadUtil").d("Bypassing cache for $mediaId - specific format requested (itag=$targetItag)")
                songUrlCache.remove(mediaId)
                // Also clear playerCache for this media to prevent format mismatch
                runBlocking(Dispatchers.IO) {
                    try {
                        if (playerCache.getCachedSpans(mediaId).isNotEmpty()) {
                            playerCache.removeResource(mediaId)
                            timber.log.Timber.tag("DownloadUtil").d("Cleared player cache for $mediaId")
                        }
                    } catch (_: Exception) {}
                }
            }

            timber.log.Timber.tag("DownloadUtil").d("Fetching fresh stream for $mediaId, targetItag=${if (hasTargetItag) targetItag else "auto"}")

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    targetItag = targetItag,
                )
            }.getOrThrow()
            val format = playbackData.format
            timber.log.Timber.tag("DownloadUtil").i(
                "Download stream for $mediaId: itag=${format.itag}, mimeType=${format.mimeType.split(";")[0]}, bitrate=${format.bitrate/1000}kbps"
            )

            // Store format synchronously to ensure it's available for metadata embedding after download
            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength!!,
                            loudnessDb = playbackData.audioConfig?.loudnessDb,
                            perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                            playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        ),
                    )

                    val now = LocalDateTime.now()
                    val existing = getSongByIdBlocking(mediaId)?.song

                    val updatedSong = if (existing != null) {
                        if (existing.dateDownload == null) {
                            existing.copy(dateDownload = now)
                        } else {
                            existing
                        }
                    } else {
                        SongEntity(
                            id = mediaId,
                            title = playbackData.videoDetails?.title ?: "Unknown",
                            duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                            thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                            dateDownload = now,
                            isDownloaded = false
                        )
                    }

                    upsert(updatedSong)

                    // Create artist relationship if song is new and has author info
                    val videoDetails = playbackData.videoDetails
                    val authorName = videoDetails?.author
                    if (existing == null && authorName != null) {
                        val channelId = videoDetails.channelId
                        // Check if artist exists, create if not
                        val existingArtist = artistByName(authorName)
                        val artistId = existingArtist?.id ?: channelId
                        if (existingArtist == null) {
                            insert(
                                com.metrolist.music.db.entities.ArtistEntity(
                                    id = artistId,
                                    name = authorName,
                                    channelId = channelId
                                )
                            )
                        }
                        // Create song-artist relationship
                        insert(
                            com.metrolist.music.db.entities.SongArtistMap(
                                songId = mediaId,
                                artistId = artistId,
                                position = 0
                            )
                        )
                    }
                }
            }

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${format.contentLength ?: 10000000}"
            }

            songUrlCache[mediaId] = streamUrl to playbackData.streamExpiresInSeconds * 1000L
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            val songId = download.request.id
                            timber.log.Timber.tag("DownloadUtil").d("onDownloadChanged: songId=$songId, state=${download.state}")

                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    timber.log.Timber.tag("DownloadUtil").d("Download completed for: $songId")
                                    // Clear target itag override now that download is done
                                    clearTargetItag(songId)
                                    database.updateDownloadedInfo(songId, true, LocalDateTime.now())

                                    // Export to custom path if enabled
                                    timber.log.Timber.tag("DownloadUtil").d(
                                        "Custom path enabled: $customDownloadPathEnabled, URI: ${customDownloadPathUri.take(50)}..."
                                    )
                                    if (customDownloadPathEnabled && customDownloadPathUri.isNotEmpty()) {
                                        timber.log.Timber.tag("DownloadUtil").d("Starting export to custom path for: $songId")
                                        try {
                                            val result = downloadExportHelper.exportToCustomPath(
                                                songId,
                                                customDownloadPathUri
                                            )
                                            if (result != null) {
                                                timber.log.Timber.tag("DownloadUtil")
                                                    .d("Export successful for $songId: $result")
                                            } else {
                                                timber.log.Timber.tag("DownloadUtil")
                                                    .w("Export returned null for: $songId")
                                            }
                                        } catch (e: Exception) {
                                            // Log error but don't fail - internal cache still works
                                            timber.log.Timber.tag("DownloadUtil")
                                                .e(e, "Failed to export to custom path for: $songId")
                                        }
                                    } else {
                                        timber.log.Timber.tag("DownloadUtil")
                                            .d("Custom path export skipped - not enabled or URI empty")
                                    }
                                }
                                Download.STATE_FAILED -> {
                                    timber.log.Timber.tag("DownloadUtil").w("Download failed for: $songId")
                                    clearTargetItag(songId)
                                    database.updateDownloadedInfo(songId, false, null)
                                }
                                Download.STATE_STOPPED -> {
                                    timber.log.Timber.tag("DownloadUtil").d("Download stopped for: $songId")
                                    clearTargetItag(songId)
                                    database.updateDownloadedInfo(songId, false, null)
                                }
                                Download.STATE_REMOVING -> {
                                    timber.log.Timber.tag("DownloadUtil").d("Download removing for: $songId")
                                    clearTargetItag(songId)
                                    database.updateDownloadedInfo(songId, false, null)

                                    // Also clean up external file if exists
                                    timber.log.Timber.tag("DownloadUtil")
                                        .d("Attempting to delete external file for: $songId")
                                    try {
                                        val deleted = downloadExportHelper.deleteFromCustomPath(songId)
                                        timber.log.Timber.tag("DownloadUtil")
                                            .d("External file deletion result: $deleted for: $songId")
                                    } catch (e: Exception) {
                                        timber.log.Timber.tag("DownloadUtil")
                                            .e(e, "Failed to delete from custom path for: $songId")
                                    }
                                }
                                else -> {
                                    timber.log.Timber.tag("DownloadUtil")
                                        .d("Unhandled download state ${download.state} for: $songId")
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }
}
