package com.courseforge.ai.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@Composable
fun VideoEngine(uri: Uri, isFullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(1f) }
    var isOverlayVisible by remember { mutableStateOf(true) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // مؤقت الإخفاء التلقائي للأزرار (يخمد بعد 3 ثوانٍ)
    LaunchedEffect(isOverlayVisible, speed, isFullscreen) {
        if (isOverlayVisible) {
            delay(3000)
            isOverlayVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { isOverlayVisible = !isOverlayVisible }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // الأزرار العائمة (تظهر وتختفي برمجياً)
        if (isOverlayVisible) {
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Button(
                    onClick = {
                        speed = if (speed == 1f) 1.5f else if (speed == 1.5f) 2f else 1f
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                        isOverlayVisible = true 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f))
                ) { Text("${speed}x", color = Color.White) }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = { onToggleFullscreen(); isOverlayVisible = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, 
                        contentDescription = "Toggle Fullscreen", 
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun PdfEngine(uri: Uri, fileId: String) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE)
    val savedPage = prefs.getInt("pdf_$fileId", 0)
    var pdfViewRef by remember { mutableStateOf<PDFView?>(null) }

    DisposableEffect(Unit) {
        onDispose { pdfViewRef?.currentPage?.let { prefs.edit().putInt("pdf_$fileId", it).apply() } }
    }

    AndroidView(
        factory = { ctx ->
            PDFView(ctx, null).apply {
                pdfViewRef = this
                // الاستدعاء الصحيح للروابط الذي يمنع خطأ المترجم
                fromUri(uri)
                    .defaultPage(savedPage)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .pageFitPolicy(FitPolicy.WIDTH)
                    .fitEachPage(true)
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
                    // تحويل المحتوى إلى Base64 لمنع أخطاء مسارات الملفات المؤقتة
                    base64Data = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_PADDING)
                }
            } catch (e: Exception) {}
        }
    }

    if (base64Data == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
            CircularProgressIndicator() 
        }
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
                            // عزل أمني: منع الوصول لأي روابط خارجية لحماية التطبيق
                            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, ByteArrayInputStream("Blocked".toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    // حقن كود الـ HTML المشفر مباشرة في الذاكرة
                    loadData(base64Data!!, "text/html; charset=utf-8", "base64")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}