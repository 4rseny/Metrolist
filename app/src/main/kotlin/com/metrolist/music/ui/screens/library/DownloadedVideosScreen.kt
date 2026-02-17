/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
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
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SelectionSongMenu
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.screens.videoRoute
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.DownloadedVideosViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadedVideosScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadedVideosViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val videos by viewModel.downloadedVideos.collectAsState()

    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    val totalLength = remember(videos) {
        videos?.fastSumBy { it.song.duration } ?: 0
    }

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

    val filteredVideos = remember(videos, query) {
        if (query.text.isEmpty()) {
            videos
        } else {
            videos?.filter { video ->
                video.song.title.contains(query.text, ignoreCase = true) ||
                    video.artists.any { it.name.contains(query.text, ignoreCase = true) }
            }
        }
    }

    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(key = "header") {
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
                        icon = R.drawable.video_library,
                        text = stringResource(R.string.no_downloaded_videos),
                    )
                }
            } else {
                itemsIndexed(
                    items = filteredVideos,
                    key = { _, video -> video.id },
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
                                M3IconButton(
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
                                        // Play video with local file
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
            scrollState = lazyListState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
        )
    }

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
                Text(stringResource(R.string.downloaded_videos))
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
            if (inSelectMode) {
                Checkbox(
                    checked = selection.size == (filteredVideos?.size ?: 0) && selection.isNotEmpty(),
                    onCheckedChange = {
                        if (selection.size == (filteredVideos?.size ?: 0)) {
                            selection.clear()
                        } else {
                            selection.clear()
                            filteredVideos?.forEach { video ->
                                selection.add(video.id)
                            }
                        }
                    }
                )
                M3IconButton(
                    enabled = selection.isNotEmpty(),
                    onClick = {
                        menuState.show {
                            SelectionSongMenu(
                                songSelection = selection.mapNotNull { id ->
                                    filteredVideos?.find { it.id == id }
                                },
                                onDismiss = menuState::dismiss,
                                clearAction = onExitSelectionMode,
                                isVideo = true,
                            )
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null
                    )
                }
            } else if (!isSearching) {
                M3IconButton(
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
}
