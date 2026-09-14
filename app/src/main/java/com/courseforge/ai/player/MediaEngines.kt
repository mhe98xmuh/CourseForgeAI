package com.courseforge.ai.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

enum class VideoScaleMode(val label: String, val resizeMode: Int) {
    FIT("افتراضي (Fit)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("تعبئة الشاشة (Crop)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("ممتد بالكامل (Fill)", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    FIXED_16_9("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    FIXED_4_3("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT)
}

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
    var currentScaleMode by remember { mutableStateOf(VideoScaleMode.FIT) }
    var showScaleMenu by remember { mutableStateOf(false) }

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
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = currentScaleMode.resizeMode
                    }
                },
                update = { view -> view.resizeMode = currentScaleMode.resizeMode },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControllerVisible = !isControllerVisible },
                        onDoubleTap = { scale = 1f; offsetX = 0f; offsetY = 0f }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        if (newScale > 1f) {
                            val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                            val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                            offsetX = (offsetX + pan.x * newScale).coerceIn(-maxOffsetX, maxOffsetX)
                            offsetY = (offsetY + pan.y * newScale).coerceIn(-maxOffsetY, maxOffsetY)
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                }
        )

        AnimatedVisibility(visible = isControllerVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Button(onClick = { showScaleMenu = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(currentScaleMode.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = showScaleMenu, onDismissRequest = { showScaleMenu = false }) {
                            VideoScaleMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label, fontWeight = if (mode == currentScaleMode) FontWeight.Bold else FontWeight.Normal, color = if (mode == currentScaleMode) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                                    onClick = { currentScaleMode = mode; showScaleMenu = false; scale = 1f; offsetX = 0f; offsetY = 0f }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                        isControllerVisible = true
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("${speed}x", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { onToggleFullscreen(); isControllerVisible = true }, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).size(38.dp)) {
                        Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = "ملء الشاشة", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }

                IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play(); isControllerVisible = true }, modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(32.dp))) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "تشغيل/إيقاف", tint = Color.White, modifier = Modifier.size(38.dp))
                }

                Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(currentTime), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = if (totalTime > 0) (currentTime.toFloat() / totalTime.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { percent ->
                            val target = (percent * totalTime).toLong()
                            exoPlayer.seekTo(target)
                            currentTime = target
                            isControllerVisible = true
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    )
                    Text(formatTime(totalTime), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun UniversalAudioPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) } }, modifier = Modifier.fillMaxWidth().height(260.dp))
    }
}

@Composable
fun PdfEngine(uri: Uri, fileId: String) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val pdfRenderer = PdfRenderer(pfd)
                    renderer = pdfRenderer
                    pageCount = pdfRenderer.pageCount
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer?.close()
        }
    }

    if (pageCount > 0 && renderer != null) {
        LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFFE0E0E0))) {
            items(pageCount) { index ->
                PdfNativePageItem(renderer = renderer!!, pageIndex = index)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun PdfNativePageItem(renderer: PdfRenderer, pageIndex: Int) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            synchronized(renderer) {
                try {
                    renderer.openPage(pageIndex).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                    }
                } catch(e: Exception) {}
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offsetX += pan.x; offsetY += pan.y
                    } else {
                        offsetX = 0f; offsetY = 0f
                    }
                }
            },
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                )
            } ?: Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
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
                    settings.apply { javaScriptEnabled = true; domStorageEnabled = true; builtInZoomControls = true; displayZoomControls = false }
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