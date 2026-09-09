package com.courseforge.ai.player

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@SuppressLint("DefaultLocale")
fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun VideoEngine(uri: Uri, isFullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(1f) }
    var isControllerVisible by remember { mutableStateOf(true) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var isPlaying by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalTime by remember { mutableLongStateOf(0L) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentTime = exoPlayer.currentPosition
            totalTime = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
            delay(500)
        }
    }

    LaunchedEffect(isControllerVisible, isPlaying) {
        if (isControllerVisible && isPlaying) {
            delay(3500)
            isControllerVisible = false
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false 
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                val surface = view.videoSurfaceView as? android.view.View
                surface?.scaleX = scale
                surface?.scaleY = scale
                surface?.translationX = offsetX
                surface?.translationY = offsetY
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControllerVisible = !isControllerVisible },
                        onDoubleTap = { 
                            scale = 1f; offsetX = 0f; offsetY = 0f 
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        )

        AnimatedVisibility(
            visible = isControllerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Button(
                        onClick = {
                            speed = if (speed == 1f) 1.5f else if (speed == 1.5f) 2f else 1f
                            exoPlayer.playbackParameters = PlaybackParameters(speed)
                            isControllerVisible = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                    ) { Text("${speed}x", color = Color.White) }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = { onToggleFullscreen(); isControllerVisible = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, 
                            contentDescription = "Toggle Fullscreen", 
                            tint = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        isControllerVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause", 
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatTime(currentTime), color = Color.White, fontWeight = FontWeight.Bold)
                    
                    Slider(
                        value = if (totalTime > 0) currentTime.toFloat() / totalTime.toFloat() else 0f,
                        onValueChange = { percent ->
                            val target = (percent * totalTime).toLong()
                            exoPlayer.seekTo(target)
                            currentTime = target
                            isControllerVisible = true
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    )
                    
                    Text(formatTime(totalTime), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AudioEngine(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxWidth().height(260.dp))
    }
}

@Composable
fun PdfEngine(uri: Uri, fileId: String) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE)
    val savedPage = prefs.getInt("pdf_$fileId", 0)

    AndroidView(
        factory = { ctx ->
            PDFView(ctx, null).apply {
                this.maxZoom = 10f
                this.midZoom = 4f
                
                fromUri(uri)
                    .defaultPage(savedPage)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .pageFitPolicy(FitPolicy.WIDTH)
                    .fitEachPage(true)
                    .onPageChange { page, _ ->
                        prefs.edit().putInt("pdf_$fileId", page).apply()
                    }
                    .load()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlEngine(uri: Uri) {
    val context = LocalContext.current
    var base64Data by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val raw = stream.bufferedReader().use { it.readText() }
                    base64Data = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_PADDING)
                }
            } catch (e: Exception) {}
        }
    }

    if (base64Data == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: ""
                            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, ByteArrayInputStream("Blocked".toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadData(base64Data!!, "text/html; charset=utf-8", "base64")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}