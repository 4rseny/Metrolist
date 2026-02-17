/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastSumBy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.screens.videoRoute
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.DownloadedScreenViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadedScreenViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    val downloadedMusic by viewModel.downloadedMusic.collectAsState()
    val downloadedVideos by viewModel.downloadedVideos.collectAsState()

    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    // Pager state: 0 = Music, 1 = Videos
    val pagerState = rememberPagerState(initialPage = 0) { 2 }

    val musicCount = downloadedMusic?.size ?: 0
    val videoCount = downloadedVideos?.size ?: 0

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }

    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val (sortType, onSortTypeChange) = rememberEnumPreference(
        SongSortTypeKey,
        SongSortType.CREATE_DATE
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)

    val filteredMusic = remember(downloadedMusic, query) {
        if (query.text.isEmpty()) {
            downloadedMusic
        } else {
            downloadedMusic?.filter { song ->
                song.song.title.contains(query.text, ignoreCase = true) ||
                    song.artists.any { it.name.contains(query.text, ignoreCase = true) }
            }
        }
    }

    val filteredVideos = remember(downloadedVideos, query) {
        if (query.text.isEmpty()) {
            downloadedVideos
        } else {
            downloadedVideos?.filter { video ->
                video.song.title.contains(query.text, ignoreCase = true) ||
                    video.artists.any { it.name.contains(query.text, ignoreCase = true) }
            }
        }
    }

    val musicLazyListState = rememberLazyListState()
    val videoLazyListState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                } else if (inSelectMode) {
                    Text(pluralStringResource(R.plurals.n_selected, selection.size, selection.size))
                } else {
                    Text(stringResource(R.string.offline))
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        when {
                            isSearching -> {
                                isSearching = false
                                query = TextFieldValue()
                            }
                            inSelectMode -> onExitSelectionMode()
                            else -> navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching && !inSelectMode) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(
                            if (isSearching || inSelectMode) R.drawable.close else R.drawable.arrow_back
                        ),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (!inSelectMode) {
                    IconButton(
                        onClick = { isSearching = !isSearching },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior,
        )

        // Tab chips for Music and Videos
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp),
        ) {
            FilterChip(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                label = { Text(stringResource(R.string.music) + " ($musicCount)") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = RoundedCornerShape(16.dp),
                border = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                label = { Text(stringResource(R.string.videos) + " ($videoCount)") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = RoundedCornerShape(16.dp),
                border = null,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> {
                    // Music tab
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = musicLazyListState,
                            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
                        ) {
                            item(key = "music_header") {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { sortType ->
                                        when (sortType) {
                                            SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                            SongSortType.NAME -> R.string.sort_by_name
                                            SongSortType.ARTIST -> R.string.sort_by_artist
                                            SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                        }
                                    },
                                )
                            }

                            if (filteredMusic.isNullOrEmpty()) {
                                item {
                                    EmptyPlaceholder(
                                        icon = R.drawable.small_icon,
                                        text = stringResource(R.string.playlist_is_empty),
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = filteredMusic,
                                    key = { _, song -> "music_${song.id}" },
                                ) { index, song ->
                                    val onCheckedChange: (Boolean) -> Unit = {
                                        if (it) {
                                            selection.add(song.id)
                                        } else {
                                            selection.remove(song.id)
                                        }
                                    }

                                    SongListItem(
                                        song = song,
                                        isActive = song.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        showInLibraryIcon = false,
                                        trailingContent = {
                                            if (inSelectMode) {
                                                Checkbox(
                                                    checked = song.id in selection,
                                                    onCheckedChange = onCheckedChange,
                                                )
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        menuState.show {
                                                            SongMenu(
                                                                originalSong = song,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.more_vert),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (inSelectMode) {
                                                        onCheckedChange(song.id !in selection)
                                                    } else if (song.id == mediaMetadata?.id) {
                                                        playerConnection.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            ListQueue(
                                                                title = "Downloaded Music",
                                                                items = filteredMusic.map { it.toMediaItem() },
                                                                startIndex = filteredMusic.indexOfFirst { it.id == song.id }
                                                            ),
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!inSelectMode) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        inSelectMode = true
                                                        onCheckedChange(true)
                                                    }
                                                },
                                            )
                                            .animateItem(),
                                    )
                                }
                            }
                        }

                        DraggableScrollbar(
                            scrollState = musicLazyListState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp),
                        )
                    }
                }
                1 -> {
                    // Videos tab
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = videoLazyListState,
                            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
                        ) {
                            item(key = "video_header") {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { sortType ->
                                        when (sortType) {
                                            SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                            SongSortType.NAME -> R.string.sort_by_name
                                            SongSortType.ARTIST -> R.string.sort_by_artist
                                            SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                        }
                                    },
                                )
                            }

                            if (filteredVideos.isNullOrEmpty()) {
                                item {
                                    EmptyPlaceholder(
                                        icon = R.drawable.small_icon,
                                        text = stringResource(R.string.no_downloaded_videos),
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = filteredVideos,
                                    key = { _, video -> "video_${video.id}" },
                                ) { index, video ->
                                    val onCheckedChange: (Boolean) -> Unit = {
                                        if (it) {
                                            selection.add(video.id)
                                        } else {
                                            selection.remove(video.id)
                                        }
                                    }

                                    SongListItem(
                                        song = video,
                                        isActive = video.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        showInLibraryIcon = false,
                                        trailingContent = {
                                            if (inSelectMode) {
                                                Checkbox(
                                                    checked = video.id in selection,
                                                    onCheckedChange = onCheckedChange,
                                                )
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        menuState.show {
                                                            SongMenu(
                                                                originalSong = video,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                                isVideo = true,
                                                            )
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.more_vert),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (inSelectMode) {
                                                        onCheckedChange(video.id !in selection)
                                                    } else {
                                                        // Play video - navigate to VideoPlayerScreen with local file
                                                        val artistDisplay = video.artists.joinToString(" \u2022 ") { it.name }
                                                        navController.navigate(
                                                            videoRoute(
                                                                videoId = video.id,
                                                                title = video.song.title,
                                                                artist = artistDisplay,
                                                                localUri = video.song.mediaStoreUri
                                                            )
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!inSelectMode) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        inSelectMode = true
                                                        onCheckedChange(true)
                                                    }
                                                },
                                            )
                                            .animateItem(),
                                    )
                                }
                            }
                        }

                        DraggableScrollbar(
                            scrollState = videoLazyListState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
