/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadedScreenViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    // Downloaded music (non-video)
    val downloadedMusic: StateFlow<List<Song>?> = context.dataStore.data
        .map {
            Triple(
                it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                it[SongSortDescendingKey] ?: true,
                it[HideExplicitKey] ?: false
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending, hideExplicit) ->
            database.downloadedMusicOnly(sortType, descending)
                .map { songs ->
                    if (hideExplicit) {
                        songs.filter { !it.song.explicit }
                    } else {
                        songs
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Downloaded videos
    val downloadedVideos: StateFlow<List<Song>?> = context.dataStore.data
        .map {
            Triple(
                it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                it[SongSortDescendingKey] ?: true,
                it[HideExplicitKey] ?: false
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending, hideExplicit) ->
            database.downloadedVideos(sortType, descending)
                .map { videos ->
                    if (hideExplicit) {
                        videos.filter { !it.song.explicit }
                    } else {
                        videos
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
}
