/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.LocalMusicRepository
import com.metrolist.music.utils.LocalSyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LocalMusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val localMusicRepository: LocalMusicRepository,
) : ViewModel() {

    init {
        Timber.d("LocalMusicViewModel: INIT - ViewModel created")
    }

    private val _hasPermission = MutableStateFlow(localMusicRepository.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    val syncStatus: StateFlow<LocalSyncStatus> = localMusicRepository.syncStatus

    val syncProgress: StateFlow<Float> = localMusicRepository.syncProgress

    val localArtists = database.localArtists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allLocalSongs = database.allLocalSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allLocalAlbums = database.allLocalAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun syncLocalMusic() {
        Timber.d("LocalMusicViewModel: syncLocalMusic() called")
        // Update permission state before syncing
        _hasPermission.value = localMusicRepository.hasPermission()
        localMusicRepository.syncLocalMusic()
        Timber.d("LocalMusicViewModel: syncLocalMusic() completed")
    }

    fun refreshPermissionState() {
        val newState = localMusicRepository.hasPermission()
        Timber.d("LocalMusicViewModel: refreshPermissionState() = $newState (was ${_hasPermission.value})")
        _hasPermission.value = newState
    }

    fun checkPermission(): Boolean {
        val result = localMusicRepository.hasPermission()
        Timber.d("LocalMusicViewModel: checkPermission() = $result")
        _hasPermission.value = result
        return result
    }

    init {
        Timber.d("LocalMusicViewModel: Secondary init block - checking permission")
        if (localMusicRepository.hasPermission()) {
            Timber.d("LocalMusicViewModel: Permission granted, auto-starting sync")
            viewModelScope.launch {
                localMusicRepository.syncLocalMusic()
            }
        } else {
            Timber.d("LocalMusicViewModel: Permission NOT granted, skipping auto-sync")
        }
    }
}

@HiltViewModel
class LocalArtistViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val artistId: String = savedStateHandle.get<String>("artistId")!!

    init {
        Timber.d("LocalArtistViewModel: INIT - artistId=$artistId")
    }

    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val albums = database.localAlbumsByArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val songs = database.localSongsByArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LocalAlbumViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val albumId: String = savedStateHandle.get<String>("albumId")!!

    init {
        Timber.d("LocalAlbumViewModel: INIT - albumId=$albumId")
    }

    val album = database.album(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs = database.localSongsByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
