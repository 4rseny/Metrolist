/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
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
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.screens.player.downloadVideo
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.utils.MediaStoreHelper
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.viewmodels.CachePlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    navController: NavController,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
    isVideo: Boolean = false, // Override to treat as video (for video sections)
    isLive: Boolean = false, // For live performances, show both song and video download options
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = database.song(originalSong.id).collectAsState(initial = originalSong)
    val song = songState.value ?: originalSong
    val download by LocalDownloadUtil.current.getDownload(originalSong.id)
        .collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val scope = rememberCoroutineScope()
    var refetchIconDegree by remember { mutableFloatStateOf(0f) }

    val cacheViewModel = hiltViewModel<CachePlaylistViewModel>()

    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    val orderedArtists by produceState(initialValue = emptyList<ArtistEntity>(), song) {
        withContext(Dispatchers.IO) {
            val artistMaps = database.songArtistMap(song.id).sortedBy { it.position }
            val sorted = artistMaps.mapNotNull { map ->
                song.artists.firstOrNull { it.id == map.artistId }
            }
            value = sorted
        }
    }

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Video download dialog state
    var showVideoDownloadDialog by remember { mutableStateOf(false) }
    var videoDownloadQualities by remember { mutableStateOf<List<YTPlayerUtils.VideoQualityInfo>>(emptyList()) }
    var isLoadingVideoQualities by remember { mutableStateOf(false) }
    var isDownloadingVideo by remember { mutableStateOf(false) }
    var videoDownloadProgress by remember { mutableStateOf<String?>(null) }
    val mediaStoreHelper = remember { MediaStoreHelper(context) }

    val TextFieldValueSaver: Saver<TextFieldValue, *> = Saver(
        save = { it.text },
        restore = { text -> TextFieldValue(text, TextRange(text.length)) }
    )

    var titleField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(song.song.title))
    }

    var artistField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(song.artists.firstOrNull()?.name.orEmpty()))
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.edit),
                    contentDescription = null
                )
            },
            title = {
                Text(text = stringResource(R.string.edit_song))
            },
            textFields = listOf(
                stringResource(R.string.song_title) to titleField,
                stringResource(R.string.artist_name) to artistField
            ),
            onTextFieldsChange = { index, newValue ->
                if (index == 0) titleField = newValue
                else artistField = newValue
            },
            onDoneMultiple = { values ->
                val newTitle = values[0]
                val newArtist = values[1]

                coroutineScope.launch {
                    database.query {
                        update(song.song.copy(title = newTitle))
                        val artist = song.artists.firstOrNull()
                        if (artist != null) {
                            update(artist.copy(name = newArtist))
                        }
                    }

                    showEditDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showEditDialog = false }
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showErrorPlaylistAddDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                }
            }
            listOf(song.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
    )

    if (showErrorPlaylistAddDialog) {
        ListDialog(
            onDismiss = {
                showErrorPlaylistAddDialog = false
                onDismiss()
            },
        ) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.already_in_playlist)) },
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier.clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(listOf(song)) { song ->
                SongListItem(song = song)
            }
        }
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = song.artists.distinctBy { it.id },
                key = { it.id },
            ) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(ListItemHeight)
                        .clickable {
                            navController.navigate("artist/${artist.id}")
                            showSelectArtistDialog = false
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = artist.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(ListThumbnailSize)
                                .clip(CircleShape),
                        )
                    }
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
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
                                    title = song.song.title,
                                    artist = artistDisplay,
                                    database = database,
                                    mediaStoreHelper = mediaStoreHelper,
                                    onProgress = { progress -> videoDownloadProgress = progress },
                                    onComplete = { success, message ->
                                        isDownloadingVideo = false
                                        showVideoDownloadDialog = false
                                        if (success) {
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        }
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

    SongListItem(
        song = song,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    val s = song.song.toggleLike()
                    database.query {
                        update(s)
                    }
                    syncUtils.likeSong(s)
                },
            ) {
                Icon(
                    painter = painterResource(if (song.song.liked) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (song.song.liked) MaterialTheme.colorScheme.error else LocalContentColor.current,
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
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.edit),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.edit),
                        onClick = { showEditDialog = true }
                    ),
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
                        onClick = { showChoosePlaylistDialog = true }
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
                            onDismiss()
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    )
                ),
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
                                val durationMs = if (song.song.duration > 0) song.song.duration.toLong() * 1000 else 180000L
                                val trackInfo = com.metrolist.music.listentogether.TrackInfo(
                                    id = song.id,
                                    title = song.song.title,
                                    artist = orderedArtists.joinToString(", ") { it.name },
                                    album = song.song.albumName,
                                    duration = durationMs,
                                    thumbnail = song.thumbnailUrl
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
                                onDismiss()
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            }
                        )
                    } else null,
                    if (!isGuest) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.play_next)) },
                            description = { Text(text = stringResource(R.string.play_next_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                playerConnection.playNext(song.toMediaItem())
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
                                onDismiss()
                                playerConnection.addToQueue(song.toMediaItem())
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
                    add(
                        Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(
                                        if (song.song.inLibrary == null) R.string.add_to_library
                                        else R.string.remove_from_library
                                    )
                                )
                            },
                            description = { Text(text = stringResource(R.string.add_to_library_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (song.song.inLibrary == null) R.drawable.library_add
                                        else R.drawable.library_add_check
                                    ),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val currentSong = song.song
                                val isInLibrary = currentSong.inLibrary != null
                                val token =
                                    if (isInLibrary) currentSong.libraryRemoveToken else currentSong.libraryAddToken

                                token?.let {
                                    coroutineScope.launch {
                                        YouTube.feedback(listOf(it))
                                    }
                                }

                                database.query {
                                    update(song.song.toggleLibrary())
                                }
                            }
                        )
                    )
                    if (event != null) {
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
                                    onDismiss()
                                    database.query {
                                        delete(event)
                                    }
                                }
                            )
                        )
                    }
                    if (playlistSong != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_playlist)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    database.transaction {
                                        coroutineScope.launch {
                                            playlistBrowseId?.let { playlistId ->
                                                if (playlistSong.map.setVideoId != null) {
                                                    YouTube.removeFromPlaylist(
                                                        playlistId,
                                                        playlistSong.map.songId,
                                                        playlistSong.map.setVideoId
                                                    )
                                                }
                                            }
                                        }
                                        move(
                                            playlistSong.map.playlistId,
                                            playlistSong.map.position,
                                            Int.MAX_VALUE
                                        )
                                        delete(playlistSong.map.copy(position = Int.MAX_VALUE))
                                    }
                                    onDismiss()
                                }
                            )
                        )
                    }
                    if (isFromCache) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_cache)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    cacheViewModel.removeSongFromCache(song.id)
                                }
                            )
                        )
                    }
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            // Check if this is a video context - use explicit parameter OR database flag
            val isVideoContext = isVideo || originalSong.song.isVideo || song.song.isVideo
            // For video context, check mediaStoreUri first (most reliable)
            val videoMediaStoreUri = originalSong.song.mediaStoreUri?.takeIf { it.isNotBlank() }
                ?: song.song.mediaStoreUri?.takeIf { it.isNotBlank() }
            val isDownloadedVideo = videoMediaStoreUri != null
            // Check if this is a video that's not downloaded yet
            val isUndownloadedVideo = isVideoContext && !isDownloadedVideo

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
                                        val downloadRequest = DownloadRequest
                                            .Builder(song.id, song.id.toUri())
                                            .setCustomCacheKey(song.id)
                                            .setData(song.song.title.toByteArray())
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
                        if (isDownloadedVideo) {
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
                                        val freshSong = database.getSongById(originalSong.id)
                                        freshSong?.song?.mediaStoreUri?.let { uriString ->
                                            val uri = android.net.Uri.parse(uriString)
                                            mediaStoreHelper.deleteVideoFromMediaStore(uri)
                                        }
                                        freshSong?.let { songData ->
                                            database.withTransaction {
                                                upsert(
                                                    songData.song.copy(
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
                                    scope.launch {
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
                            // Downloaded video - show remove option
                            isDownloadedVideo -> {
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
                                        // Use a scope that won't be cancelled when menu dismisses
                                        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                                            // Fetch fresh data from database to get correct URI
                                            val freshSong = database.getSongById(originalSong.id)
                                            val uriToDelete = freshSong?.song?.mediaStoreUri
                                                ?: videoMediaStoreUri

                                            // Delete from MediaStore
                                            uriToDelete?.let { uriString ->
                                                val uri = android.net.Uri.parse(uriString)
                                                mediaStoreHelper.deleteVideoFromMediaStore(uri)
                                            }
                                            // Clear the database entry using withTransaction to wait for completion and trigger Flow invalidation
                                            freshSong?.let { songData ->
                                                database.withTransaction {
                                                    upsert(
                                                        songData.song.copy(
                                                            mediaStoreUri = null
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        onDismiss()
                                    }
                                )
                            }
                            // Video not downloaded - show download video option with quality selector
                            isUndownloadedVideo -> {
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
                                        scope.launch {
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
                            // Regular audio download completed
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
                                        val downloadRequest =
                                            DownloadRequest
                                                .Builder(song.id, song.id.toUri())
                                                .setCustomCacheKey(song.id)
                                                .setData(song.song.title.toByteArray())
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
                                if (song.artists.size == 1) {
                                    navController.navigate("artist/${song.artists[0].id}")
                                    onDismiss()
                                } else {
                                    showSelectArtistDialog = true
                                }
                            }
                        )
                    )
                    if (song.song.albumId != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_album)) },
                                description = {
                                    song.song.albumName?.let {
                                        Text(text = it)
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    navController.navigate("album/${song.song.albumId}")
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.refetch)) },
                            description = { Text(text = stringResource(R.string.refetch_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.sync),
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer(rotationZ = rotationAnimation),
                                )
                            },
                            onClick = {
                                refetchIconDegree -= 360
                                scope.launch(Dispatchers.IO) {
                                    YouTube.queue(listOf(song.id)).onSuccess {
                                        val newSong = it.firstOrNull()
                                        if (newSong != null) {
                                            database.transaction {
                                                update(song, newSong.toMediaMetadata())
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    )
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
