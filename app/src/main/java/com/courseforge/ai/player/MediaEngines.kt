package com.courseforge.ai.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

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