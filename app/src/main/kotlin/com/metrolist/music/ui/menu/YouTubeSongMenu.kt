/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.screens.player.downloadVideo
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.MediaStoreHelper
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@SuppressLint("MutableCollectionMutableState")
@Composable
fun YouTubeSongMenu(
    song: SongItem,
    navController: NavController,
    onDismiss: () -> Unit,
    onHistoryRemoved: () -> Unit = {},
    isVideo: Boolean = false, // Override to treat as video (for video sections)
    isLive: Boolean = false, // For live performances, show both song and video download options
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(song.id).collectAsState(initial = null)
    val download by LocalDownloadUtil.current.getDownload(song.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val artists = remember {
        song.artists.mapNotNull {
            it.id?.let { artistId ->
                MediaMetadata.Artist(id = artistId, name = it.name)
            }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {  
        mutableStateOf(false)  
    }  

    AddToPlaylistDialog(  
        isVisible = showChoosePlaylistDialog,  
        onGetSong = { playlist ->  
            database.transaction {  
                insert(song.toMediaMetadata())  
            }  
            coroutineScope.launch(Dispatchers.IO) {  
                playlist.playlist.browseId?.let { browseId ->  
                    YouTube.addToPlaylist(browseId, song.id)  
                }  
            }  
            listOf(song.id)  
        },  
        onDismiss = { showChoosePlaylistDialog = false }  
    )  

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Video download dialog state
    var showVideoDownloadDialog by remember { mutableStateOf(false) }
    var videoDownloadQualities by remember { mutableStateOf<List<YTPlayerUtils.VideoQualityInfo>>(emptyList()) }
    var isLoadingVideoQualities by remember { mutableStateOf(false) }
    var isDownloadingVideo by remember { mutableStateOf(false) }
    var videoDownloadProgress by remember { mutableStateOf<String?>(null) }
    val mediaStoreHelper = remember { MediaStoreHelper(context) }

    // Check if this is a video item
    val isVideoItem = isVideo || song.isVideoSong

    if (showSelectArtistDialog) {  
        ListDialog(  
            onDismiss = { showSelectArtistDialog = false },  
        ) {  
            items(artists) { artist ->  
                Row(  
                    verticalAlignment = Alignment.CenterVertically,  
                    modifier =  
                    Modifier  
                        .height(ListItemHeight)  
                        .clickable {  
                            navController.navigate("artist/${artist.id}")  
                            showSelectArtistDialog = false  
                            onDismiss()  
                        }  
                        .padding(horizontal = 12.dp),  
                ) {  
                    Box(  
                        contentAlignment = Alignment.CenterStart,  
                        modifier =  
                        Modifier  
                            .fillParentMaxWidth()  
                            .height(ListItemHeight)  
                            .clickable {  
                                navController.navigate("artist/${artist.id}")  
                                showSelectArtistDialog = false  
                                onDismiss()  
                            }  
                            .padding(horizontal = 24.dp),  
                    ) {  
                        Text(  
                            text = artist.name,  
                            fontSize = 18.sp,  
                            fontWeight = FontWeight.Bold,  
                            maxLines = 1,  
                            overflow = TextOverflow.Ellipsis,  
                        )  
                    }  
                }
            }
        }
    }

    // Video download quality selector dialog
    if (showVideoDownloadDialog) {
        DefaultDialog(
            onDismiss = { if (!isDownloadingVideo) showVideoDownloadDialog = false },
            title = { Text(stringResource(if (isDownloadingVideo) R.string.downloading else R.string.video_download_quality)) },
            buttons = {
                if (isDownloadingVideo) {
                    TextButton(onClick = {
                        // Dismiss dialog but continue download in background
                        showVideoDownloadDialog = false
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.background))
                    }
                }
                TextButton(onClick = { showVideoDownloadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            if (isLoadingVideoQualities) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (isDownloadingVideo) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = videoDownloadProgress ?: stringResource(R.string.downloading),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (videoDownloadQualities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    videoDownloadQualities.forEach { quality ->
                        Surface(
                            onClick = {
                                isDownloadingVideo = true
                                val artistDisplay = song.artists.joinToString(" • ") { it.name }
                                downloadVideo(
                                    context = context,
                                    videoId = song.id,
                                    targetHeight = quality.height,
                                    title = song.title,
                                    artist = artistDisplay,
                                    database = database,
                                    mediaStoreHelper = mediaStoreHelper,
                                    onProgress = { progress -> videoDownloadProgress = progress },
                                    onComplete = { success, message ->
                                        isDownloadingVideo = false
                                        showVideoDownloadDialog = false
                                        Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                        onDismiss()
                                    }
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_video_hd),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                                Column {
                                    Text(
                                        text = "${quality.height}p",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = quality.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.no_video_qualities),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    ListItem(  
        headlineContent = {
            Text(
                text = song.title,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },  
        supportingContent = {  
            Text(  
                text = joinByBullet(
                    song.artists.joinToString { it.name },
                    song.duration?.let { makeTimeString(it * 1000L) },
                )
            )  
        },  
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
            ) {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                )
            }
        },
        trailingContent = {  
            IconButton(  
                onClick = {  
                    database.transaction {  
                        librarySong.let { librarySong ->  
                            val s: SongEntity  
                            if (librarySong == null) {  
                                insert(song.toMediaMetadata(), SongEntity::toggleLike)  
                                s = song.toMediaMetadata().toSongEntity().let(SongEntity::toggleLike)  
                            } else {  
                                s = librarySong.song.toggleLike()  
                                update(s)  
                            }  
                            syncUtils.likeSong(s)  
                        }  
                    }  
                },  
            ) {  
                Icon(  
                    painter = painterResource(if (librarySong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border),  
                    tint = if (librarySong?.song?.liked == true) MaterialTheme.colorScheme.error else LocalContentColor.current,  
                    contentDescription = null,  
                )  
            }  
        },  
    )  

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val bottomSheetPageState = LocalBottomSheetPageState.current
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            NewActionGrid(
                actions = listOfNotNull(
                    if (!isGuest) {
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = stringResource(R.string.play_next),
                            onClick = {
                                playerConnection.playNext(song.copy(thumbnail = song.thumbnail.resize(544,544)).toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null,
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = {
                            showChoosePlaylistDialog = true
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.share),
                        onClick = {
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, song.shareLink)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                            onDismiss()
                        }
                    )
                ),
                columns = if (isGuest) 2 else 3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
            )
        }

        item {
            Material3MenuGroup(
                items = listOfNotNull(
                    if (listenTogetherManager != null && listenTogetherManager.isInRoom && !listenTogetherManager.isHost) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.suggest_to_host)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val durationMs = if (song.duration != null && song.duration!! > 0) song.duration!! * 1000L else 180000L
                                val trackInfo = com.metrolist.music.listentogether.TrackInfo(
                                    id = song.id,
                                    title = song.title,
                                    artist = artists.joinToString(", ") { it.name },
                                    album = song.album?.name,
                                    duration = durationMs,
                                    thumbnail = song.thumbnail
                                )
                                listenTogetherManager.suggestTrack(trackInfo)
                                onDismiss()
                            }
                        )
                    } else null,
                    if (!isGuest) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.start_radio)) },
                            description = { Text(text = stringResource(R.string.start_radio_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.radio),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                onDismiss()
                            }
                        )
                    } else null,
                    if (!isGuest) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.add_to_queue)) },
                            description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                playerConnection.addToQueue(song.toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    if (song.historyRemoveToken != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_history)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    coroutineScope.launch {
                                        YouTube.feedback(listOf(song.historyRemoveToken!!))
                                        delay(500)
                                        onHistoryRemoved()
                                        onDismiss()
                                    }
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = {
                                Text(text = if (librarySong?.song?.inLibrary != null) stringResource(R.string.remove_from_library) else stringResource(R.string.add_to_library))
                            },
                            description = { Text(text = stringResource(R.string.add_to_library_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(if (librarySong?.song?.inLibrary != null) R.drawable.library_add_check else R.drawable.library_add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val isInLibrary = librarySong?.song?.inLibrary != null

                                // Use the new reliable method that fetches fresh tokens
                                coroutineScope.launch {
                                    YouTube.toggleSongLibrary(song.id, !isInLibrary)
                                }

                                if (isInLibrary) {
                                    database.query {
                                        inLibrary(song.id, null)
                                    }
                                } else {
                                    database.transaction {
                                        insert(song.toMediaMetadata())
                                        inLibrary(song.id, LocalDateTime.now())
                                        addLibraryTokens(
                                            song.id,
                                            song.libraryAddToken,
                                            song.libraryRemoveToken
                                        )
                                    }
                                }
                            }
                        )
                    )
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            // For live performances, show both song and video download/remove options
            if (isLive) {
                Material3MenuGroup(
                    items = buildList {
                        // Song download/remove option
                        when {
                            download?.state == Download.STATE_COMPLETED -> {
                                add(Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_download) + " (${stringResource(R.string.music)})") },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.offline),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
                                    }
                                ))
                            }
                            download?.state == Download.STATE_QUEUED || download?.state == Download.STATE_DOWNLOADING -> {
                                add(Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.downloading) + " (${stringResource(R.string.music)})") },
                                    icon = {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    },
                                    onClick = {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
                                    }
                                ))
                            }
                            else -> {
                                add(Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.download_song)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        database.transaction {
                                            insert(song.toMediaMetadata())
                                        }
                                        val downloadRequest = DownloadRequest
                                            .Builder(song.id, song.id.toUri())
                                            .setCustomCacheKey(song.id)
                                            .setData(song.title.toByteArray())
                                            .build()
                                        DownloadService.sendAddDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            downloadRequest,
                                            false,
                                        )
                                    }
                                ))
                            }
                        }
                        // Video download/remove option
                        // Check if video is downloaded by looking at mediaStoreUri (video downloads are stored in MediaStore)
                        val videoDownloaded = !librarySong?.song?.mediaStoreUri.isNullOrBlank()
                        if (videoDownloaded) {
                            add(Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_download) + " (${stringResource(R.string.videos)})") },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                                        val freshSong = database.getSongById(song.id)
                                        freshSong?.song?.mediaStoreUri?.let { uriString ->
                                            val uri = android.net.Uri.parse(uriString)
                                            mediaStoreHelper.deleteVideoFromMediaStore(uri)
                                        }
                                        freshSong?.let { songData ->
                                            database.withTransaction {
                                                upsert(
                                                    songData.song.copy(
                                                        isDownloaded = false,
                                                        dateDownload = null,
                                                        mediaStoreUri = null
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    onDismiss()
                                }
                            ))
                        } else {
                            add(Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.video_download)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showVideoDownloadDialog = true
                                    isLoadingVideoQualities = true
                                    coroutineScope.launch {
                                        try {
                                            val adaptiveData = withContext(Dispatchers.IO) {
                                                YTPlayerUtils.getAdaptiveVideoData(
                                                    videoId = song.id,
                                                    targetHeight = null,
                                                    preferMp4 = true
                                                ).getOrNull()
                                            }
                                            videoDownloadQualities = adaptiveData?.availableQualities ?: emptyList()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            isLoadingVideoQualities = false
                                        }
                                    }
                                }
                            ))
                        }
                    }
                )
            } else {
                Material3MenuGroup(
                    items = listOf(
                        when {
                            // Video item - show video download option
                            isVideoItem -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.video_download)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        // Show quality selector dialog
                                        showVideoDownloadDialog = true
                                        isLoadingVideoQualities = true
                                        coroutineScope.launch {
                                            try {
                                                val adaptiveData = withContext(Dispatchers.IO) {
                                                    YTPlayerUtils.getAdaptiveVideoData(
                                                        videoId = song.id,
                                                        targetHeight = null,
                                                        preferMp4 = true
                                                    ).getOrNull()
                                                }
                                                videoDownloadQualities = adaptiveData?.availableQualities ?: emptyList()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            } finally {
                                                isLoadingVideoQualities = false
                                            }
                                        }
                                    }
                                )
                            }
                            // Audio download completed
                            download?.state == Download.STATE_COMPLETED -> {
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text = stringResource(R.string.remove_download)
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.offline),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
                                    }
                                )
                            }
                            download?.state == Download.STATE_QUEUED || download?.state == Download.STATE_DOWNLOADING -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.downloading)) },
                                    icon = {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    },
                                    onClick = {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
                                    }
                                )
                            }
                            else -> {
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.action_download)) },
                                    description = { Text(text = stringResource(R.string.download_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        database.transaction {
                                            insert(song.toMediaMetadata())
                                        }
                                        val downloadRequest = DownloadRequest
                                            .Builder(song.id, song.id.toUri())
                                            .setCustomCacheKey(song.id)
                                            .setData(song.title.toByteArray())
                                            .build()
                                        DownloadService.sendAddDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            downloadRequest,
                                            false,
                                        )
                                    }
                                )
                            }
                        }
                    )
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    if (artists.isNotEmpty()) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_artist)) },
                                description = { Text(text = song.artists.joinToString { it.name }) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    if (artists.size == 1) {
                                        navController.navigate("artist/${artists[0].id}")
                                        onDismiss()
                                    } else {
                                        showSelectArtistDialog = true
                                    }
                                }
                            )
                        )
                    }
                    song.album?.let { album ->
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_album)) },
                                description = { Text(text = album.name) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    navController.navigate("album/${album.id}")
                                    onDismiss()
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
                            description = { Text(text = stringResource(R.string.details_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.info),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                bottomSheetPageState.show {
                                    ShowMediaInfo(song.id)
                                }
                            }
                        )
                    )
                }
            )
        }
    }
}
