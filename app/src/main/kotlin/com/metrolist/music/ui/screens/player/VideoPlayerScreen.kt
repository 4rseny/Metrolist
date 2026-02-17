/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.utils.MediaStoreHelper
import com.metrolist.music.utils.UrlValidator
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import io.sanghun.compose.video.RepeatMode
import io.sanghun.compose.video.VideoPlayer
import io.sanghun.compose.video.controller.VideoPlayerControllerConfig
import io.sanghun.compose.video.uri.VideoPlayerMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    navController: NavController,
    videoId: String,
    title: String? = null,
    artist: String? = null,
    localUri: String? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val connectivityManager = remember { context.getSystemService(ConnectivityManager::class.java) }
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    // Use rememberSaveable for state that should persist across rotation
    var videoItem by remember { mutableStateOf<VideoPlayerMediaItem.NetworkMediaItem?>(null) }
    var playerInstance by remember { mutableStateOf<ExoPlayer?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var loadError by rememberSaveable { mutableStateOf<String?>(null) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentTitle by rememberSaveable(videoId, title) { mutableStateOf(title?.takeIf { it.isNotBlank() }) }
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    var availableQualities by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var selectedQualityId by rememberSaveable { mutableStateOf("auto") }
    // Adaptive playback data for 1080p+ support
    var adaptiveData by remember { mutableStateOf<YTPlayerUtils.AdaptiveVideoData?>(null) }
    var adaptiveQualities by remember { mutableStateOf<List<YTPlayerUtils.VideoQualityInfo>>(emptyList()) }
    var selectedQualityHeight by rememberSaveable { mutableStateOf(1080) } // Default to 1080p
    var playbackInfo by rememberSaveable { mutableStateOf<String?>(null) }
    var isInPipMode by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var artistName by rememberSaveable(videoId, artist) { mutableStateOf(artist?.takeIf { it.isNotBlank() }) }
    var positionMs by rememberSaveable { mutableStateOf(0L) }
    var durationMs by rememberSaveable { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var videoBottomPx by remember { mutableStateOf<Int?>(null) }

    // Download state
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadQualities by remember { mutableStateOf<List<YTPlayerUtils.VideoQualityInfo>>(emptyList()) }
    var isLoadingDownloadQualities by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<String?>(null) }

    val mediaStoreHelper = remember { MediaStoreHelper(context) }

    // Auto-fullscreen on landscape orientation
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(isLandscape, isFullscreen) {
        val act = activity ?: return@LaunchedEffect
        val window = act.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isLandscape && !isFullscreen && !isInPipMode) {
            isFullscreen = true
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else if (!isLandscape && isFullscreen) {
            isFullscreen = false
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Restore system bars when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(videoId) {
        val mappedSong = withContext(Dispatchers.IO) {
            val direct = database.getSongByIdBlocking(videoId)
            if (direct != null) return@withContext direct
            val setVideo = database.getSetVideoId(videoId)?.setVideoId
            if (setVideo != null) database.getSongById(setVideo) else null
        }
        mappedSong?.let { song ->
            if (currentTitle.isNullOrBlank()) {
                currentTitle = song.song.title
            }
            if (artistName.isNullOrBlank()) {
                val artistDisplay = song.artists.joinToString(" • ") { it.name }
                artistName = artistDisplay.ifBlank { null }
            }
        }
    }

    val httpClient = remember {
        OkHttpClient.Builder()
            .build()
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Pause any music playback while the user is watching video
    LaunchedEffect(Unit) {
        playerConnection?.player?.pause()
    }

    DisposableEffect(playerInstance) {
        val player = playerInstance ?: return@DisposableEffect onDispose { }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val qualities = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group ->
                        val mtg = group.mediaTrackGroup
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            val height = format.height.takeIf { it > 0 }
                            val bitrate = format.bitrate.takeIf { it > 0 }
                            val label = buildString {
                                if (height != null) append("${height}p ")
                                if (bitrate != null) append("(${bitrate / 1000}kbps) ")
                                if (!format.codecs.isNullOrBlank()) append(format.codecs)
                            }.ifBlank { "Video" }
                            QualityOption(
                                id = "${mtg.hashCode()}_$index",
                                label = label.trim(),
                                height = height,
                                width = format.width.takeIf { it > 0 },
                                bitrate = bitrate,
                                codecs = format.codecs,
                                mimeType = format.sampleMimeType,
                                group = mtg,
                                trackIndex = index
                            )
                        }
                    }
                    .sortedByDescending { it.height ?: 0 }
                availableQualities = qualities

                val currentOverrideEntry = player.trackSelectionParameters.overrides.entries.firstOrNull { entry ->
                    qualities.any { it.group == entry.key }
                }
                selectedQualityId = currentOverrideEntry?.let { entry ->
                    val match = qualities.firstOrNull { opt ->
                        opt.group == entry.key && entry.value.trackIndices.contains(opt.trackIndex)
                    }
                    match?.id
                } ?: "auto"

                val format = player.videoFormat
                playbackInfo = format?.let { f ->
                    val resolution = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null
                    val bitrateKbps = f.bitrate.takeIf { it > 0 }?.div(1000)
                    val codec = when {
                        !f.codecs.isNullOrBlank() -> f.codecs
                        !f.sampleMimeType.isNullOrBlank() -> f.sampleMimeType
                        else -> null
                    }
                    buildString {
                        resolution?.let { append(it) }
                        bitrateKbps?.let {
                            if (isNotEmpty()) append(" • ")
                            append("${it}kbps")
                        }
                        codec?.let {
                            if (isNotEmpty()) append(" • ")
                            append(it)
                        }
                    }.ifBlank { null }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(playerConnection?.mediaMetadata?.value, videoId) {
        val meta = playerConnection?.mediaMetadata?.value ?: return@LaunchedEffect
        if (meta.id == videoId || meta.setVideoId == videoId) {
            currentTitle = meta.title
            val artistDisplay = meta.artists.joinToString(" • ") { it.name }
            artistName = artistDisplay.ifBlank { artistName }
        }
    }

    val maxVideoBitrateKbps = remember(connectivityManager) {
        if (connectivityManager?.isActiveNetworkMetered == true) 1500 else 6000
    }
    val supportsPip = remember(activity) {
        activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    }
    val canEnterPip by remember {
        derivedStateOf {
            supportsPip &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                videoItem != null &&
                loadError == null
        }
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, _ ->
            isInPipMode = activity?.isInPictureInPictureMode == true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    LaunchedEffect(videoId, maxVideoBitrateKbps, reloadKey, localUri) {
        isLoading = true
        loadError = null
        videoItem = null
        adaptiveData = null

        // If we have a local URI (downloaded video), play it directly
        if (!localUri.isNullOrBlank()) {
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(currentTitle ?: videoId)
                .apply {
                    artistName?.let { setArtist(it) }
                }
                .build()

            videoItem = VideoPlayerMediaItem.NetworkMediaItem(
                url = localUri,
                mediaMetadata = mediaMetadata,
                mimeType = "video/mp4",
                drmConfiguration = null
            )
            isLoading = false
            return@LaunchedEffect
        }

        // Try adaptive playback first (for 1080p+ support)
        // Quality selection is handled by the adaptive player, not by refetching
        val adaptiveResult = withContext(Dispatchers.IO) {
            YTPlayerUtils.getAdaptiveVideoData(videoId, targetHeight = selectedQualityHeight)
        }

        adaptiveResult.onSuccess { adaptive ->
            val videoUrl = UrlValidator.validateAndParseUrl(adaptive.videoUrl)?.toString()
            val audioUrl = UrlValidator.validateAndParseUrl(adaptive.audioUrl)?.toString()

            if (videoUrl != null && audioUrl != null) {
                adaptiveData = adaptive
                adaptiveQualities = adaptive.availableQualities

                val titleFromPlayback = adaptive.videoDetails?.title?.takeIf { it.isNotBlank() }
                val resolvedTitle = titleFromPlayback ?: currentTitle ?: videoId
                currentTitle = resolvedTitle

                // Get artist name, avoiding channel IDs (which start with "UC" and have no spaces)
                val authorFromPlayback = adaptive.videoDetails?.author?.takeIf {
                    it.isNotBlank() && !it.isChannelId()
                }
                val artistFromTitle = resolvedTitle.extractArtistFromTitle()

                if (artistName.isNullOrBlank() || artistName?.isChannelId() == true) {
                    artistName = authorFromPlayback ?: artistFromTitle ?: "Unknown Artist"
                }
                val thumbnail = adaptive.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(resolvedTitle)
                    .apply {
                        thumbnail?.let { setArtworkUri(Uri.parse(it)) }
                        artistName?.let { setArtist(it) }
                    }
                    .build()

                // Use video URL as placeholder - we'll set MergingMediaSource after player is created
                videoItem = VideoPlayerMediaItem.NetworkMediaItem(
                    url = videoUrl,
                    mediaMetadata = mediaMetadata,
                    mimeType = adaptive.videoFormat.mimeType,
                    drmConfiguration = null
                )
                isLoading = false
                return@LaunchedEffect
            }
        }

        // Fallback to progressive playback (limited to 720p)
        // Uses isVideoFallback=true to skip TVHTML5 (already tried for adaptive)
        val result = withContext(Dispatchers.IO) {
            val cm = connectivityManager ?: error("No connectivity manager")
            YTPlayerUtils.playerResponseForVideoPlayback(
                videoId = videoId,
                connectivityManager = cm,
                maxVideoBitrateKbps = maxVideoBitrateKbps,
                isVideoFallback = true,
            )
        }

        result.onSuccess { playback ->
            val validatedUrl = UrlValidator.validateAndParseUrl(playback.streamUrl)?.toString()
            if (validatedUrl == null) {
                loadError = "Invalid stream URL"
                isLoading = false
                return@onSuccess
            }

            val titleFromPlayback = playback.videoDetails?.title?.takeIf { it.isNotBlank() }
            val resolvedTitle = titleFromPlayback ?: currentTitle ?: videoId
            currentTitle = resolvedTitle

            // Get artist name, avoiding channel IDs
            val authorFromPlayback = playback.videoDetails?.author?.takeIf {
                it.isNotBlank() && !it.isChannelId()
            }
            val artistFromTitle = resolvedTitle.extractArtistFromTitle()

            if (artistName.isNullOrBlank() || artistName?.isChannelId() == true) {
                artistName = authorFromPlayback ?: artistFromTitle ?: "Unknown Artist"
            }
            val thumbnail = playback.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url

            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(resolvedTitle)
                .apply {
                    thumbnail?.let { setArtworkUri(Uri.parse(it)) }
                    artistName?.let { setArtist(it) }
                }
                .build()

            videoItem = VideoPlayerMediaItem.NetworkMediaItem(
                url = validatedUrl,
                mediaMetadata = mediaMetadata,
                mimeType = playback.format.mimeType,
                drmConfiguration = null
            )
            isLoading = false
        }.onFailure {
            loadError = it.localizedMessage ?: "Playback error"
            isLoading = false
        }
    }

    LaunchedEffect(playerInstance) {
        val player = playerInstance ?: return@LaunchedEffect
        while (isActive) {
            if (!isScrubbing) {
                positionMs = player.currentPosition
            }
            val d = player.duration
            if (d > 0) durationMs = d
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    val enterPip: () -> Unit = pip@{
        val act = activity ?: return@pip
        if (!canEnterPip) return@pip
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        } else {
            null
        }
        try {
            @Suppress("DEPRECATION")
            val entered = if (params != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.enterPictureInPictureMode(params)
            } else {
                act.enterPictureInPictureMode()
                true
            }
            if (!entered) {
                Toast.makeText(context, "Unable to start PiP", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IllegalStateException) {
            Toast.makeText(context, "PiP unavailable: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    val markInteraction: () -> Unit = {
        showControls = true
        lastInteraction = System.currentTimeMillis()
    }

    val togglePlayPause: () -> Unit = {
        playerInstance?.let { player ->
            if (player.isPlaying) {
                player.pause()
                showControls = true
            } else {
                player.play()
                markInteraction()
            }
        }
    }

    val seekByMs: (Long) -> Unit = { delta ->
        playerInstance?.let { player ->
            val durationLimit = if (durationMs > 0) durationMs else Long.MAX_VALUE
            val newPos = (player.currentPosition + delta).coerceIn(0, durationLimit)
            player.seekTo(newPos)
            positionMs = newPos
            lastInteraction = System.currentTimeMillis()
            showControls = true
        }
    }

    val toggleFullscreen: () -> Unit = fullscreen@{
        val act = activity ?: return@fullscreen
        val next = !isFullscreen
        isFullscreen = next
        act.requestedOrientation = if (next) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Hide/show system bars for true fullscreen
        val window = act.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (next) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val density = LocalDensity.current
    val dragSkipThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }
    var dragAccum by remember { mutableStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            showControls = true
        } else {
            markInteraction()
        }
    }

    LaunchedEffect(showControls, lastInteraction, isPlaying) {
        if (!showControls) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        delay(4000)
        if (System.currentTimeMillis() - lastInteraction >= 3800 && isPlaying) {
            showControls = false
        }
    }

    BackHandler(enabled = !isInPipMode) {
        navController.popBackStack()
    }

    Scaffold(containerColor = Color.Black) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                loadError != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = loadError ?: "Playback error", color = Color.White)
                        TextButton(onClick = { reloadKey++ }) {
                            Text("Retry", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                videoItem != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .padding(vertical = if (isInPipMode) 0.dp else 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .onGloballyPositioned { coords ->
                                    videoBottomPx = coords.boundsInParent().bottom.toInt()
                                }
                        ) {
                            // Use custom ExoPlayer for adaptive playback (1080p+)
                            if (adaptiveData != null) {
                                val adaptive = adaptiveData!!

                                // Create stable OkHttpClient and DataSourceFactory
                                val okHttpClient = remember {
                                    OkHttpClient.Builder()
                                        .proxy(YouTube.proxy)
                                        .build()
                                }

                                val dataSourceFactory = remember(okHttpClient) {
                                    val requestHeaders = mutableMapOf<String, String>()
                                    requestHeaders["Origin"] = "https://www.youtube.com"
                                    requestHeaders["Referer"] = "https://www.youtube.com/"
                                    YouTube.cookie?.let { requestHeaders["Cookie"] = it }

                                    OkHttpDataSource.Factory(okHttpClient)
                                        .setUserAgent(YouTubeClient.USER_AGENT_WEB)
                                        .setDefaultRequestProperties(requestHeaders)
                                }

                                // Create player once per videoId (not per quality change)
                                val adaptivePlayer = remember(videoId) {
                                    ExoPlayer.Builder(context).build().apply {
                                        playWhenReady = true
                                        addListener(object : androidx.media3.common.Player.Listener {
                                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                                Timber.tag("VideoPlayer").e(error, "Playback error: ${error.message}")
                                            }
                                        })
                                    }.also {
                                        Timber.tag("VideoPlayer").d("Created stable ExoPlayer for videoId=$videoId")
                                    }
                                }

                                // Update media source when URLs change (quality switch)
                                LaunchedEffect(adaptive.videoUrl, adaptive.audioUrl) {
                                    val currentPos = adaptivePlayer.currentPosition
                                    val wasPlaying = adaptivePlayer.isPlaying

                                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(MediaItem.fromUri(adaptive.videoUrl))
                                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(MediaItem.fromUri(adaptive.audioUrl))

                                    val mergingSource = MergingMediaSource(videoSource, audioSource)

                                    adaptivePlayer.setMediaSource(mergingSource)
                                    adaptivePlayer.prepare()

                                    // Restore position on quality changes (not initial load)
                                    if (currentPos > 0) {
                                        adaptivePlayer.seekTo(currentPos)
                                    }
                                    adaptivePlayer.playWhenReady = wasPlaying || currentPos == 0L

                                    Timber.tag("VideoPlayer").d("Updated media source: video=${adaptive.videoFormat.height}p, restored pos=${currentPos}ms")
                                }

                                // Set playerInstance for controls
                                LaunchedEffect(adaptivePlayer) {
                                    playerInstance = adaptivePlayer
                                }

                                // Cleanup only when videoId changes or screen is disposed
                                DisposableEffect(videoId) {
                                    onDispose {
                                        Timber.tag("VideoPlayer").d("Releasing ExoPlayer for videoId=$videoId")
                                        adaptivePlayer.release()
                                        if (playerInstance == adaptivePlayer) {
                                            playerInstance = null
                                        }
                                    }
                                }

                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            player = adaptivePlayer
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    update = { playerView ->
                                        if (playerView.player != adaptivePlayer) {
                                            playerView.player = adaptivePlayer
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(adaptivePlayer) {
                                            detectTapGestures {
                                                // Tap toggles controls visibility
                                                showControls = !showControls
                                                if (showControls) {
                                                    lastInteraction = System.currentTimeMillis()
                                                }
                                            }
                                        }
                                        .pointerInput(adaptivePlayer, durationMs) {
                                            detectDragGestures(
                                                onDrag = { _, dragAmount ->
                                                    dragAccum += dragAmount.x
                                                    markInteraction()
                                                },
                                                onDragEnd = {
                                                    // Swipe left/right to seek
                                                    when {
                                                        dragAccum > dragSkipThresholdPx -> seekByMs(10_000)
                                                        dragAccum < -dragSkipThresholdPx -> seekByMs(-10_000)
                                                    }
                                                    dragAccum = 0f
                                                },
                                                onDragCancel = {
                                                    dragAccum = 0f
                                                }
                                            )
                                        }
                                )
                            } else if (videoItem != null) {
                                // Fallback to library's VideoPlayer for progressive streams (720p max)
                                VideoPlayer(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(playerInstance) {
                                            detectTapGestures {
                                                // Tap toggles controls visibility
                                                showControls = !showControls
                                                if (showControls) {
                                                    lastInteraction = System.currentTimeMillis()
                                                }
                                            }
                                        }
                                        .pointerInput(playerInstance, durationMs) {
                                            detectDragGestures(
                                                onDrag = { _, dragAmount ->
                                                    dragAccum += dragAmount.x
                                                    markInteraction()
                                                },
                                                onDragEnd = {
                                                    // Swipe left/right to seek
                                                    when {
                                                        dragAccum > dragSkipThresholdPx -> seekByMs(10_000)
                                                        dragAccum < -dragSkipThresholdPx -> seekByMs(-10_000)
                                                    }
                                                    dragAccum = 0f
                                                },
                                                onDragCancel = {
                                                    dragAccum = 0f
                                                }
                                            )
                                        },
                                    mediaItems = listOf(videoItem!!),
                                    handleLifecycle = false,
                                    autoPlay = true,
                                    usePlayerController = false,
                                    controllerConfig = VideoPlayerControllerConfig.Default,
                                    repeatMode = RepeatMode.NONE,
                                    enablePip = false,
                                    enablePipWhenBackPressed = false,
                                    playerInstance = { playerInstance = this }
                                )
                            }
                        }

                        if (!isInPipMode) {
                            val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                            val pillBg = MaterialTheme.colorScheme.surface
                            val pillBorder = outlineColor
                            val chipRowOffsetPx = videoBottomPx?.plus(with(density) { 8.dp.roundToPx() })

                            AnimatedVisibility(
                                visible = showControls,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 0.dp)
                            ) {
                                Surface(
                                    shape = RectangleShape,
                                    color = Color.Black.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                markInteraction()
                                                navController.popBackStack()
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.arrow_back),
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                        Text(
                                            text = currentTitle ?: videoId,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (supportsPip) {
                                            IconButton(
                                                onClick = {
                                                    markInteraction()
                                                    enterPip()
                                                },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.08f))
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_pip),
                                                    contentDescription = null,
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                markInteraction()
                                                val clip = ClipData.newPlainText("Video link", "https://music.youtube.com/watch?v=$videoId")
                                                clipboard?.setPrimaryClip(clip)
                                                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.link),
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            if (chipRowOffsetPx != null) {
                                AnimatedVisibility(
                                    visible = showControls,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset { IntOffset(0, chipRowOffsetPx) }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = currentTitle ?: videoId,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = artistName ?: "Unknown artist",
                                                color = Color.LightGray,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        // Only show download and quality buttons for streaming (not downloaded) videos
                                        if (localUri.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // Download button
                                            Surface(
                                                shape = RoundedCornerShape(24.dp),
                                                color = pillBg,
                                                border = BorderStroke(1.dp, pillBorder),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        markInteraction()
                                                        if (!isDownloading) {
                                                            isLoadingDownloadQualities = true
                                                            showDownloadDialog = true
                                                            scope.launch {
                                                                try {
                                                                    // Use getAdaptiveVideoData to fetch available qualities
                                                                    val adaptiveData = withContext(Dispatchers.IO) {
                                                                        YTPlayerUtils.getAdaptiveVideoData(
                                                                            videoId = videoId,
                                                                            targetHeight = null,
                                                                            preferMp4 = true
                                                                        ).getOrNull()
                                                                    }
                                                                    downloadQualities = adaptiveData?.availableQualities ?: emptyList()
                                                                } catch (e: Exception) {
                                                                    e.printStackTrace()
                                                                } finally {
                                                                    isLoadingDownloadQualities = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    enabled = !isDownloading
                                                ) {
                                                    if (isDownloading) {
                                                        // Parse percentage from progress string (e.g., "Downloading 1080p: 45%")
                                                        val progressPercent = downloadProgress?.let { progress ->
                                                            val match = Regex("(\\d+)%").find(progress)
                                                            match?.groupValues?.get(1)?.toIntOrNull()
                                                        }
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier.size(28.dp)
                                                        ) {
                                                            if (progressPercent != null) {
                                                                CircularProgressIndicator(
                                                                    progress = { progressPercent / 100f },
                                                                    modifier = Modifier.size(28.dp),
                                                                    color = Color.White,
                                                                    trackColor = Color.White.copy(alpha = 0.3f),
                                                                    strokeWidth = 2.dp
                                                                )
                                                                Text(
                                                                    text = "$progressPercent",
                                                                    color = Color.White,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontSize = 8.sp
                                                                )
                                                            } else {
                                                                CircularProgressIndicator(
                                                                    modifier = Modifier.size(20.dp),
                                                                    color = Color.White,
                                                                    strokeWidth = 2.dp
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        Icon(
                                                            painter = painterResource(R.drawable.download),
                                                            contentDescription = stringResource(R.string.video_download)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // Quality button
                                            Surface(
                                                shape = RoundedCornerShape(24.dp),
                                                color = pillBg,
                                                border = BorderStroke(1.dp, pillBorder),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                IconButton(onClick = {
                                                    markInteraction()
                                                    showQualityDialog = true
                                                }) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_video_hd),
                                                        contentDescription = stringResource(R.string.video_quality)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = showControls,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                val sliderValue =
                                    if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
                                val durationText = if (durationMs > 0) formatTime(durationMs) else "--:--"
                                val positionText = formatTime(positionMs)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .border(
                                            width = 1.dp,
                                            color = outlineColor,
                                            shape = RoundedCornerShape(18.dp)
                                        )
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                                ) {
                                    val buttonColors = IconButtonDefaults.outlinedIconButtonColors(
                                        contentColor = Color.White,
                                        containerColor = Color.Black.copy(alpha = 0.35f)
                                    )
                                    val buttonBorder = BorderStroke(1.dp, outlineColor)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedIconButton(
                                            onClick = { showSpeedDialog = true },
                                            modifier = Modifier.size(36.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.speed),
                                                contentDescription = stringResource(R.string.video_speed)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { seekByMs(-10_000) },
                                            modifier = Modifier.size(52.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(18.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.skip_previous),
                                                contentDescription = stringResource(R.string.video_previous)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = togglePlayPause,
                                            modifier = Modifier.size(64.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(22.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                                contentDescription = stringResource(if (isPlaying) R.string.video_pause else R.string.video_play)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { seekByMs(10_000) },
                                            modifier = Modifier.size(52.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(18.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.skip_next),
                                                contentDescription = stringResource(R.string.video_next)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = toggleFullscreen,
                                            modifier = Modifier.size(36.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.fullscreen),
                                                contentDescription = stringResource(R.string.video_fullscreen)
                                            )
                                        }
                                    }

                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { value ->
                                            if (durationMs > 0) {
                                                isScrubbing = true
                                                positionMs = (durationMs * value).toLong().coerceIn(0, durationMs)
                                            }
                                            lastInteraction = System.currentTimeMillis()
                                        },
                                        onValueChangeFinished = {
                                            if (durationMs > 0) {
                                                playerInstance?.seekTo(positionMs)
                                            }
                                            isScrubbing = false
                                        },
                                        enabled = durationMs > 0,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        )
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(positionText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                        Text(durationText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val currentSpeed = playerInstance?.playbackParameters?.speed ?: 1f
        DefaultDialog(
            onDismiss = { showSpeedDialog = false },
            title = { Text(stringResource(R.string.playback_speed)) },
            buttons = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                speeds.forEach { speed ->
                    Surface(
                        onClick = {
                            playerInstance?.setPlaybackSpeed(speed)
                            showSpeedDialog = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentSpeed == speed)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (speed == 1f) "1.0x (Normal)" else "${speed}x",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (currentSpeed == speed) {
                                Icon(
                                    painter = painterResource(R.drawable.done),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        val playingQuality = adaptiveData?.videoFormat?.height ?: selectedQualityHeight
        DefaultDialog(
            onDismiss = { showQualityDialog = false },
            title = { Text(stringResource(R.string.video_quality)) },
            buttons = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Show current playing quality
                Text(
                    text = "Playing: ${playingQuality}p",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Use adaptive qualities if available (1080p+ support)
                if (adaptiveQualities.isNotEmpty()) {
                    adaptiveQualities.forEach { quality ->
                        Surface(
                            onClick = {
                                selectedQualityHeight = quality.height
                                showQualityDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (playingQuality == quality.height)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
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
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
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
                                if (playingQuality == quality.height) {
                                    Icon(
                                        painter = painterResource(R.drawable.done),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                } else if (availableQualities.isNotEmpty()) {
                    // Fallback to ExoPlayer track selection (progressive streams)
                    Surface(
                        onClick = {
                            playerInstance?.let { player ->
                                val params = player.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .build()
                                player.trackSelectionParameters = params
                            }
                            selectedQualityId = "auto"
                            showQualityDialog = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedQualityId == "auto")
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Auto", style = MaterialTheme.typography.titleMedium)
                            if (selectedQualityId == "auto") {
                                Icon(
                                    painter = painterResource(R.drawable.done),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    availableQualities.forEach { option ->
                        Surface(
                            onClick = {
                                playerInstance?.let { player ->
                                    val builder = player.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        .setOverrideForType(
                                            TrackSelectionOverride(option.group, listOf(option.trackIndex))
                                        )
                                    player.trackSelectionParameters = builder.build()
                                    selectedQualityId = option.id
                                }
                                showQualityDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedQualityId == option.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = option.label.ifBlank { "Track ${option.trackIndex + 1}" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (selectedQualityId == option.id) {
                                    Icon(
                                        painter = painterResource(R.drawable.done),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
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
    }

    // Fetch download qualities when dialog opens
    LaunchedEffect(showDownloadDialog) {
        if (showDownloadDialog && downloadQualities.isEmpty() && !isLoadingDownloadQualities) {
            isLoadingDownloadQualities = true
            val result = withContext(Dispatchers.IO) {
                YTPlayerUtils.getAdaptiveVideoData(videoId, targetHeight = null, preferMp4 = true)
            }
            result.onSuccess { data ->
                downloadQualities = data.availableQualities
            }
            isLoadingDownloadQualities = false
        }
    }

    // Download dialog
    if (showDownloadDialog) {
        DefaultDialog(
            onDismiss = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.video_download_quality)) },
            buttons = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            if (isLoadingDownloadQualities) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (downloadQualities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    downloadQualities.forEach { quality ->
                        Surface(
                            onClick = {
                                showDownloadDialog = false
                                downloadVideo(
                                    context = context,
                                    videoId = videoId,
                                    targetHeight = quality.height,
                                    title = currentTitle ?: videoId,
                                    artist = artistName ?: "",
                                    durationSec = (durationMs / 1000).toInt(),
                                    database = database,
                                    mediaStoreHelper = mediaStoreHelper,
                                    scope = scope,
                                    onStart = {
                                        isDownloading = true
                                        downloadProgress = context.getString(R.string.video_downloading)
                                    },
                                    onProgress = { progress -> downloadProgress = progress },
                                    onComplete = { success, message ->
                                        isDownloading = false
                                        downloadProgress = null
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = quality.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.video_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

fun downloadVideo(
    context: android.content.Context,
    videoId: String,
    targetHeight: Int,
    title: String,
    artist: String,
    durationSec: Int = -1,
    database: com.metrolist.music.db.MusicDatabase,
    mediaStoreHelper: MediaStoreHelper,
    scope: kotlinx.coroutines.CoroutineScope? = null,
    onStart: () -> Unit = {},
    onProgress: (String) -> Unit,
    onComplete: (success: Boolean, message: String) -> Unit,
) {
    val coroutineScope = scope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    coroutineScope.launch {
        onStart()
        try {
            val useProgressive = targetHeight <= 720

            if (useProgressive) {
                // Progressive download (720p and below)
                downloadProgressiveVideo(
                    context, videoId, targetHeight, title, artist, durationSec,
                    database, mediaStoreHelper, onProgress, onComplete
                )
            } else {
                // Adaptive download with muxing (1080p+)
                downloadAdaptiveVideo(
                    context, videoId, targetHeight, title, artist, durationSec,
                    database, mediaStoreHelper, onProgress, onComplete
                )
            }
        } catch (e: Exception) {
            Timber.tag("VideoDownload").e(e, "Download failed")
            onComplete(false, context.getString(R.string.video_download_failed))
        }
    }
}

private suspend fun downloadProgressiveVideo(
    context: android.content.Context,
    videoId: String,
    targetHeight: Int,
    title: String,
    artist: String,
    durationSec: Int,
    database: com.metrolist.music.db.MusicDatabase,
    mediaStoreHelper: MediaStoreHelper,
    onProgress: (String) -> Unit,
    onComplete: (success: Boolean, message: String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw Exception("No connectivity manager")

        val bitrateKbps = when {
            targetHeight >= 720 -> 2500
            targetHeight >= 480 -> 1000
            else -> 500
        }

        onProgress("Fetching stream...")

        val playback = YTPlayerUtils.playerResponseForVideoPlayback(
            videoId = videoId,
            connectivityManager = connectivityManager,
            maxVideoBitrateKbps = bitrateKbps,
            isVideoFallback = false
        ).getOrThrow()

        val streamUrl = UrlValidator.validateAndParseUrl(playback.streamUrl)?.toString()
            ?: throw Exception("Invalid stream URL")

        val qualityLabel = playback.format.height?.let { "${it}p" } ?: "${targetHeight}p"

        onProgress("Downloading ${qualityLabel}...")

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        val tempFile = File(context.cacheDir, "temp_video_$videoId.mp4")

        val request = Request.Builder().url(streamUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val contentLength = response.body?.contentLength() ?: -1L
            response.body?.byteStream()?.use { input ->
                tempFile.outputStream().use { output ->
                    copyWithProgress(input, output, contentLength) { percent ->
                        withContext(Dispatchers.Main) {
                            onProgress("Downloading $qualityLabel: $percent%")
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.Main) { onProgress("Saving...") }

        val fileName = "$title ($qualityLabel).mp4".take(200)
        val uri = mediaStoreHelper.saveVideoToMediaStore(
            tempFile = tempFile,
            fileName = fileName,
            mimeType = "video/mp4",
            title = title,
            artist = artist,
            durationMs = durationSec * 1000L
        )

        tempFile.delete()

        if (uri != null) {
            database.withTransaction {
                // Insert or update song entry
                val existingSong = getSongByIdBlocking(videoId)
                if (existingSong != null) {
                    // For existing songs (like live performances), only update mediaStoreUri
                    // Don't change isVideo flag so audio downloads still work
                    updateMediaStoreUri(
                        songId = videoId,
                        mediaStoreUri = uri.toString()
                    )
                } else {
                    insert(
                        SongEntity(
                            id = videoId,
                            title = title,
                            duration = durationSec,
                            thumbnailUrl = playback.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                            explicit = false,
                            dateDownload = LocalDateTime.now(),
                            isDownloaded = true,
                            isVideo = true,
                            mediaStoreUri = uri.toString()
                        )
                    )
                }
                // Always ensure artist association exists (IGNORE handles duplicates)
                // Use videoDetails.channelId and author, or fall back to the passed artist parameter
                val channelId = playback.videoDetails?.channelId
                val authorName = playback.videoDetails?.author ?: artist.takeIf { it.isNotBlank() }
                if (channelId != null && authorName != null) {
                    insert(ArtistEntity(id = channelId, name = authorName))
                    insert(SongArtistMap(songId = videoId, artistId = channelId, position = 0))
                }
            }
            withContext(Dispatchers.Main) { onComplete(true, context.getString(R.string.video_download_complete)) }
        } else {
            withContext(Dispatchers.Main) { onComplete(false, context.getString(R.string.video_download_failed)) }
        }
    } catch (e: Exception) {
        Timber.tag("VideoDownload").e(e, "Progressive download failed")
        withContext(Dispatchers.Main) { onComplete(false, context.getString(R.string.video_download_failed)) }
    }
}

private suspend fun downloadAdaptiveVideo(
    context: android.content.Context,
    videoId: String,
    targetHeight: Int,
    title: String,
    artist: String,
    durationSec: Int,
    database: com.metrolist.music.db.MusicDatabase,
    mediaStoreHelper: MediaStoreHelper,
    onProgress: (String) -> Unit,
    onComplete: (success: Boolean, message: String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        onProgress("Fetching streams...")

        val adaptiveResult = YTPlayerUtils.getAdaptiveVideoData(
            videoId = videoId,
            targetHeight = targetHeight,
            preferMp4 = true
        )

        adaptiveResult.onSuccess { adaptive ->
            val videoUrl = UrlValidator.validateAndParseUrl(adaptive.videoUrl)?.toString()
                ?: throw Exception("Invalid video URL")
            val audioUrl = UrlValidator.validateAndParseUrl(adaptive.audioUrl)?.toString()
                ?: throw Exception("Invalid audio URL")

            val qualityLabel = "${adaptive.videoFormat.height}p"

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()

            val requestHeaders = mutableMapOf(
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to com.metrolist.innertube.models.YouTubeClient.USER_AGENT_WEB
            )
            YouTube.cookie?.let { requestHeaders["Cookie"] = it }

            val tempVideoFile = File(context.cacheDir, "temp_video_${videoId}_video.mp4")
            val tempAudioFile = File(context.cacheDir, "temp_video_${videoId}_audio.m4a")
            val tempMuxedFile = File(context.cacheDir, "temp_video_${videoId}_muxed.mp4")

            // Get content lengths for progress tracking
            val videoHeadRequest = Request.Builder()
                .url(videoUrl)
                .head()
                .apply { requestHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            val audioHeadRequest = Request.Builder()
                .url(audioUrl)
                .head()
                .apply { requestHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val videoSize = try {
                httpClient.newCall(videoHeadRequest).execute().use { it.header("Content-Length")?.toLongOrNull() ?: -1L }
            } catch (e: Exception) { -1L }
            val audioSize = try {
                httpClient.newCall(audioHeadRequest).execute().use { it.header("Content-Length")?.toLongOrNull() ?: -1L }
            } catch (e: Exception) { -1L }

            val totalSize = if (videoSize > 0 && audioSize > 0) videoSize + audioSize else -1L
            var downloadedBytes = 0L

            // Download video stream
            val videoRequest = Request.Builder()
                .url(videoUrl)
                .apply { requestHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            httpClient.newCall(videoRequest).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Video download failed: HTTP ${response.code}")
                val contentLength = response.body?.contentLength() ?: videoSize
                response.body?.byteStream()?.use { input ->
                    tempVideoFile.outputStream().use { output ->
                        copyWithProgress(input, output, contentLength) { percent ->
                            val overallPercent = if (totalSize > 0) {
                                ((downloadedBytes + (contentLength * percent / 100)) * 100 / totalSize).toInt()
                            } else {
                                percent / 2  // Estimate 50% for video
                            }
                            withContext(Dispatchers.Main) {
                                onProgress("Downloading $qualityLabel: $overallPercent%")
                            }
                        }
                    }
                }
                downloadedBytes += contentLength
            }

            // Download audio stream
            val audioRequest = Request.Builder()
                .url(audioUrl)
                .apply { requestHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            httpClient.newCall(audioRequest).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Audio download failed: HTTP ${response.code}")
                val contentLength = response.body?.contentLength() ?: audioSize
                response.body?.byteStream()?.use { input ->
                    tempAudioFile.outputStream().use { output ->
                        copyWithProgress(input, output, contentLength) { percent ->
                            val overallPercent = if (totalSize > 0) {
                                ((downloadedBytes + (contentLength * percent / 100)) * 100 / totalSize).toInt()
                            } else {
                                50 + percent / 2  // Estimate 50-100% for audio
                            }
                            withContext(Dispatchers.Main) {
                                onProgress("Downloading $qualityLabel: $overallPercent%")
                            }
                        }
                    }
                }
            }

            // Mux video and audio
            withContext(Dispatchers.Main) { onProgress(context.getString(R.string.video_muxing)) }
            muxVideoAudio(tempVideoFile, tempAudioFile, tempMuxedFile)

            withContext(Dispatchers.Main) { onProgress("Saving...") }
            val fileName = "$title ($qualityLabel).mp4".take(200)
            val uri = mediaStoreHelper.saveVideoToMediaStore(
                tempFile = tempMuxedFile,
                fileName = fileName,
                mimeType = "video/mp4",
                title = title,
                artist = artist,
                durationMs = durationSec * 1000L
            )

            // Clean up temp files
            tempVideoFile.delete()
            tempAudioFile.delete()
            tempMuxedFile.delete()

            if (uri != null) {
                database.withTransaction {
                    // Insert or update song entry
                    val existingSong = getSongByIdBlocking(videoId)
                    if (existingSong != null) {
                        // For existing songs (like live performances), only update mediaStoreUri
                        // Don't change isVideo flag so audio downloads still work
                        updateMediaStoreUri(
                            songId = videoId,
                            mediaStoreUri = uri.toString()
                        )
                    } else {
                        insert(
                            SongEntity(
                                id = videoId,
                                title = title,
                                duration = durationSec,
                                thumbnailUrl = adaptive.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                                explicit = false,
                                dateDownload = LocalDateTime.now(),
                                isDownloaded = true,
                                isVideo = true,
                                mediaStoreUri = uri.toString()
                            )
                        )
                    }
                    // Always ensure artist association exists (IGNORE handles duplicates)
                    // Use videoDetails.channelId and author, or fall back to the passed artist parameter
                    val channelId = adaptive.videoDetails?.channelId
                    val authorName = adaptive.videoDetails?.author ?: artist.takeIf { it.isNotBlank() }
                    if (channelId != null && authorName != null) {
                        insert(ArtistEntity(id = channelId, name = authorName))
                        insert(SongArtistMap(songId = videoId, artistId = channelId, position = 0))
                    }
                }
                withContext(Dispatchers.Main) { onComplete(true, context.getString(R.string.video_download_complete)) }
            } else {
                withContext(Dispatchers.Main) { onComplete(false, context.getString(R.string.video_download_failed)) }
            }
        }.onFailure { e ->
            Timber.tag("VideoDownload").e(e, "Adaptive download failed")
            withContext(Dispatchers.Main) { onComplete(false, context.getString(R.string.video_download_failed)) }
        }
    } catch (e: Exception) {
        Timber.tag("VideoDownload").e(e, "Adaptive download failed")
        withContext(Dispatchers.Main) { onComplete(false, context.getString(R.string.video_download_failed)) }
    }
}

private fun muxVideoAudio(videoFile: File, audioFile: File, outputFile: File) {
    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()

    try {
        videoExtractor.setDataSource(videoFile.absolutePath)
        audioExtractor.setDataSource(audioFile.absolutePath)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Add video track
        var videoTrackIndex = -1
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoExtractor.selectTrack(i)
                videoTrackIndex = muxer.addTrack(format)
                break
            }
        }

        // Add audio track
        var audioTrackIndex = -1
        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioExtractor.selectTrack(i)
                audioTrackIndex = muxer.addTrack(format)
                break
            }
        }

        if (videoTrackIndex < 0 || audioTrackIndex < 0) {
            throw Exception("Could not find video or audio track")
        }

        muxer.start()

        val bufferSize = 1024 * 1024 // 1MB buffer
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        // Write video samples
        while (true) {
            buffer.clear()
            val sampleSize = videoExtractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = videoExtractor.sampleTime
            bufferInfo.flags = videoExtractor.sampleFlags

            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            videoExtractor.advance()
        }

        // Write audio samples
        while (true) {
            buffer.clear()
            val sampleSize = audioExtractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = audioExtractor.sampleFlags

            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
            audioExtractor.advance()
        }

        muxer.stop()
        muxer.release()
    } finally {
        videoExtractor.release()
        audioExtractor.release()
    }
}

private suspend fun copyWithProgress(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    totalBytes: Long,
    onProgress: suspend (Int) -> Unit
) {
    val buffer = ByteArray(8192)
    var bytesRead: Int
    var totalRead = 0L
    var lastReportedPercent = -1

    while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
        totalRead += bytesRead

        if (totalBytes > 0) {
            val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (percent != lastReportedPercent && percent % 5 == 0) {
                lastReportedPercent = percent
                onProgress(percent)
            }
        }
    }
    // Final progress update
    if (totalBytes > 0 && lastReportedPercent != 100) {
        onProgress(100)
    }
}

private data class QualityOption(
    val id: String,
    val label: String,
    val height: Int?,
    val width: Int?,
    val bitrate: Int?,
    val codecs: String?,
    val mimeType: String?,
    val group: TrackGroup,
    val trackIndex: Int,
)

@Composable
private fun formatTime(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Check if a string looks like a YouTube channel ID.
 * Channel IDs start with "UC" and are alphanumeric with no spaces.
 */
private fun String.isChannelId(): Boolean {
    return this.startsWith("UC") && this.length >= 20 && !this.contains(" ")
}

/**
 * Extract artist name from video title.
 * Assumes format "Artist - Title" or "Artist | Title".
 */
private fun String.extractArtistFromTitle(): String? {
    // Try common separators
    val separators = listOf(" - ", " – ", " — ", " | ", " // ")
    for (separator in separators) {
        if (this.contains(separator)) {
            val artist = this.substringBefore(separator).trim()
            // Make sure we got something reasonable (not empty, not too long)
            if (artist.isNotBlank() && artist.length < 100) {
                return artist
            }
        }
    }
    return null
}
