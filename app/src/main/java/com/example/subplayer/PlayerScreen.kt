package com.example.subplayer

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(uri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val srt = remember(uri) { SubtitleGenerator.srtFile(ctx, uri) }

    var hasSub by remember { mutableStateOf(srt.exists()) }
    var subsOn by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var askKey by remember { mutableStateOf(false) }
    var showUi by remember { mutableStateOf(true) }
    var fast by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(true) }

    val player = remember { ExoPlayer.Builder(ctx).build() }

    // (Re)load the video; attach the generated .srt if it exists. Keeps position.
    fun load(withSub: Boolean) {
        val p = player.currentPosition
        val play = player.playWhenReady
        val item = MediaItem.Builder().setUri(uri)
        if (withSub && srt.exists()) {
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srt))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        player.setMediaItem(item.build(), p)
        player.prepare()
        player.playWhenReady = play
    }

    fun seekBy(ms: Long) {
        val d = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, d))
        flash = if (ms > 0) "+10s" else "-10s"
    }

    fun startGeneration(key: String) {
        generating = true
        scope.launch {
            try {
                SubtitleGenerator.generate(ctx, uri, key, srt) { status = it }
                hasSub = true
                subsOn = true
                load(true)
                status = "Subtitles ready"
                delay(2500)
                status = null
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
            } finally {
                generating = false
            }
        }
    }

    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val c = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(srt.exists())
        player.playWhenReady = true
        onDispose {
            player.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            c.show(WindowInsetsCompat.Type.systemBars())
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(subsOn) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build()
    }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = if (player.duration > 0) player.duration else 1L
            playing = player.playWhenReady
            delay(300)
        }
    }
    LaunchedEffect(showUi, playing) {
        if (showUi && playing) { delay(3500); showUi = false }
    }
    LaunchedEffect(flash) {
        if (flash != null) { delay(700); flash = null }
    }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        )

        // Gesture layer + controls
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showUi = !showUi },
                    onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000L else 10_000L) },
                    onLongPress = { fast = true; player.setPlaybackSpeed(2f) },
                    onPress = {
                        tryAwaitRelease()
                        if (fast) { fast = false; player.setPlaybackSpeed(1f) }
                    }
                )
            }
        ) {
            // top bar
            AnimatedVisibility(showUi, Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x66000000)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        when {
                            generating -> {}
                            hasSub -> subsOn = !subsOn
                            else -> {
                                val key = prefs.getString("groq_key", "").orEmpty()
                                if (key.isBlank()) askKey = true else startGeneration(key)
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.ClosedCaption, "Subtitles",
                            tint = if (hasSub && subsOn) Color(0xFF4CAF50) else Color.White
                        )
                    }
                    IconButton(onClick = { askKey = true }) {
                        Icon(Icons.Filled.Settings, "API key", tint = Color.White)
                    }
                }
            }

            // bottom controls
            AnimatedVisibility(showUi, Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(pos), color = Color.White)
                        Slider(
                            value = pos.toFloat().coerceIn(0f, dur.toFloat()),
                            onValueChange = { pos = it.toLong(); player.seekTo(it.toLong()) },
                            valueRange = 0f..dur.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(fmt(dur), color = Color.White)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { seekBy(-10_000L) }) {
                            Icon(Icons.Filled.Replay10, "Back 10s", tint = Color.White)
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = {
                            player.playWhenReady = !player.playWhenReady
                            playing = player.playWhenReady
                        }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play/Pause", tint = Color.White, modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = { seekBy(10_000L) }) {
                            Icon(Icons.Filled.Forward10, "Forward 10s", tint = Color.White)
                        }
                    }
                }
            }

            // overlays
            if (fast) Badge("2x", Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            flash?.let { Badge(it, Modifier.align(Alignment.Center)) }
            status?.let { Badge(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) }
        }
    }

    if (askKey) {
        var key by remember { mutableStateOf(prefs.getString("groq_key", "").orEmpty()) }
        AlertDialog(
            onDismissRequest = { askKey = false },
            title = { Text("Groq API key") },
            text = {
                Column {
                    Text("Free key from console.groq.com. Stored only on this phone.")
                    OutlinedTextField(key, { key = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("groq_key", key.trim()).apply()
                    askKey = false
                    if (!hasSub && !generating && key.isNotBlank()) startGeneration(key.trim())
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askKey = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Box(
        modifier.background(Color(0xAA000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = Color.White) }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
