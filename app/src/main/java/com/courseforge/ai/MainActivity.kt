package com.courseforge.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class FileType { PDF, VIDEO, AUDIO, HTML, UNKNOWN }
enum class AiProvider { GROQ, GEMINI, OPENAI, ANTHROPIC, OPENROUTER }

data class Course(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class CourseItem(
    val id: String,
    val courseId: String,
    val name: String,
    val uriString: String,
    val type: FileType,
    val orderIndex: Int,
    val isCompleted: Boolean = false
)

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

data class CourseSummary(
    val overview: String,
    val keyPoints: List<String>,
    val questions: List<QuizQuestion>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CourseForgeMainNavigation()
                }
            }
        }
    }
}

@Composable
fun CourseForgeMainNavigation() {
    val context = LocalContext.current
    var courses by remember { mutableStateOf(loadCourses(context)) }
    var allItems by remember { mutableStateOf(loadAllCourseItems(context)) }
    var activeCourse by remember { mutableStateOf<Course?>(null) }
    var selectedItem by remember { mutableStateOf<CourseItem?>(null) }
    var showAiSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedItem != null || activeCourse != null) {
        if (selectedItem != null) {
            selectedItem = null
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else if (activeCourse != null) {
            activeCourse = null
        }
    }

    if (selectedItem != null) {
        ContentPlayerScreen(
            item = selectedItem!!,
            onBack = {
                selectedItem = null
                (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        )
    } else if (activeCourse != null) {
        CourseDetailScreen(
            course = activeCourse!!,
            allItems = allItems,
            onUpdateItems = { updated ->
                allItems = updated
                saveAllCourseItems(context, updated)
            },
            onOpenItem = { item -> selectedItem = item },
            onBack = { activeCourse = null }
        )
    } else {
        CoursesListScreen(
            courses = courses,
            allItems = allItems,
            onSelectCourse = { activeCourse = it },
            onCreateCourse = { name ->
                val newCourse = Course(id = UUID.randomUUID().toString(), title = name)
                courses = courses + newCourse
                saveCourses(context, courses)
            },
            onDeleteCourse = { course ->
                courses = courses.filterNot { it.id == course.id }
                allItems = allItems.filterNot { it.courseId == course.id }
                saveCourses(context, courses)
                saveAllCourseItems(context, allItems)
            },
            onOpenSettings = { showAiSettings = true }
        )
    }

    if (showAiSettings) {
        AiConfigurationDialog(onDismiss = { showAiSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesListScreen(
    courses: List<Course>,
    allItems: List<CourseItem>,
    onSelectCourse: (Course) -> Unit,
    onCreateCourse: (String) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CourseForge AI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Course")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (courses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد دورات حالياً. اضغط على أيقونة المجلد لإضافة دورة جديدة.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(courses) { course ->
                        val itemsForCourse = allItems.filter { it.courseId == course.id }
                        val total = itemsForCourse.size
                        val completed = itemsForCourse.count { it.isCompleted }
                        val progress = if (total > 0) completed.toFloat() / total else 0f

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable { onSelectCourse(course) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(course.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { onDeleteCourse(course) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.weight(1f).height(6.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("${(progress * 100).toInt()}% ($completed/$total)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text("إنشاء دورة أو مجلد جديد") },
                    text = {
                        OutlinedTextField(
                            value = newCourseName,
                            onValueChange = { newCourseName = it },
                            label = { Text("اسم الدورة (مثلاً: دورة المحاسبة)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newCourseName.isNotBlank()) {
                                    onCreateCourse(newCourseName.trim())
                                    newCourseName = ""
                                    showCreateDialog = false
                                }
                            }
                        ) { Text("إنشاء") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateDialog = false }) { Text("إلغاء") }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    course: Course,
    allItems: List<CourseItem>,
    onUpdateItems: (List<CourseItem>) -> Unit,
    onOpenItem: (CourseItem) -> Unit,
    onBack: () -> Unit
) {
    val courseItems = remember(allItems, course.id) {
        allItems.filter { it.courseId == course.id }.sortedBy { it.orderIndex }
    }

    val total = courseItems.size
    val completed = courseItems.count { it.isCompleted }
    val progress = if (total > 0) completed.toFloat() / total else 0f

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val newEntries = uris.mapIndexed { idx, uri ->
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "درس_${System.currentTimeMillis()}"
            val type = when {
                fileName.endsWith(".pdf", true) -> FileType.PDF
                fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".webm", true) -> FileType.VIDEO
                fileName.endsWith(".mp3", true) || fileName.endsWith(".wav", true) || fileName.endsWith(".m4a", true) -> FileType.AUDIO
                fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> FileType.HTML
                else -> FileType.UNKNOWN
            }
            CourseItem(
                id = UUID.randomUUID().toString(),
                courseId = course.id,
                name = fileName,
                uriString = uri.toString(),
                type = type,
                orderIndex = courseItems.size + idx,
                isCompleted = false
            )
        }
        onUpdateItems(allItems + newEntries)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Lessons")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("نسبة الإكمال", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${(progress * 100).toInt()}% ($completed/$total)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
            }

            if (courseItems.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("لا توجد ملفات في هذه الدورة. اضغط + لإضافة دروس.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(courseItems) { index, item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onOpenItem(item) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.isCompleted,
                                    onCheckedChange = { checked ->
                                        val updated = allItems.map {
                                            if (it.id == item.id) it.copy(isCompleted = checked) else it
                                        }
                                        onUpdateItems(updated)
                                    }
                                )
                                Icon(
                                    when (item.type) {
                                        FileType.PDF -> Icons.Default.PictureAsPdf
                                        FileType.VIDEO -> Icons.Default.VideoLibrary
                                        FileType.AUDIO -> Icons.Default.AudioFile
                                        FileType.HTML -> Icons.Default.Language
                                        FileType.UNKNOWN -> Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    item.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                IconButton(onClick = {
                                    if (index > 0) {
                                        val mutable = courseItems.toMutableList()
                                        val prev = mutable[index - 1]
                                        mutable[index - 1] = item.copy(orderIndex = index - 1)
                                        mutable[index] = prev.copy(orderIndex = index)
                                        val others = allItems.filterNot { it.courseId == course.id }
                                        onUpdateItems(others + mutable)
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                                }

                                IconButton(onClick = {
                                    if (index < courseItems.size - 1) {
                                        val mutable = courseItems.toMutableList()
                                        val next = mutable[index + 1]
                                        mutable[index + 1] = item.copy(orderIndex = index + 1)
                                        mutable[index] = next.copy(orderIndex = index)
                                        val others = allItems.filterNot { it.courseId == course.id }
                                        onUpdateItems(others + mutable)
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Down")
                                }

                                IconButton(onClick = {
                                    val updated = allItems.filterNot { it.id == item.id }
                                    onUpdateItems(updated)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContentPlayerScreen(item: CourseItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uri = Uri.parse(item.uriString)

    var isFullscreen by remember { mutableStateOf(false) }
    var activeSummary by remember { mutableStateOf<CourseSummary?>(null) }
    var isProcessingAi by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = isFullscreen || activeSummary != null) {
        if (activeSummary != null) {
            activeSummary = null
        } else if (isFullscreen) {
            isFullscreen = false
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (activeSummary != null) {
        SummaryAndQuizScreen(summary = activeSummary!!, onClose = { activeSummary = null })
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullscreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        item.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        coroutineScope.launch {
                            isProcessingAi = true
                            statusMessage = "جاري استخراج النص والتحليل..."
                            val extracted = extractTextContent(context, item) { statusMessage = it }
                            if (extracted.isNotBlank()) {
                                statusMessage = "جاري صياغة الاختبار والملخص..."
                                activeSummary = runAiAnalysis(context, extracted)
                            }
                            isProcessingAi = false
                            statusMessage = null
                        }
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تحليل ذكي")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (item.type) {
                    FileType.VIDEO -> UniversalVideoPlayer(
                        uri = uri,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = {
                            val target = !isFullscreen
                            isFullscreen = target
                            val act = context as? Activity
                            if (target) {
                                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    )
                    FileType.AUDIO -> UniversalAudioPlayer(uri = uri)
                    FileType.PDF -> HighResZoomablePdfViewer(uri = uri)
                    FileType.HTML -> LocalHtmlViewer(uri = uri)
                    FileType.UNKNOWN -> Text("تنسيق غير مدعوم للمعاينة المباشرة", modifier = Modifier.align(Alignment.Center))
                }

                if (isProcessingAi) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(statusMessage ?: "جاري المعالجة...", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UniversalVideoPlayer(
    uri = Uri,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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

        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        ) {
            Icon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = "Toggle Fullscreen",
                tint = Color.White
            )
        }
    }
}

@Composable
fun UniversalAudioPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )
    }
}

@Composable
fun HighResZoomablePdfViewer(uri: Uri) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val list = mutableListOf<Bitmap>()
                        val count = minOf(renderer.pageCount, 30)

                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                val pageScale = screenWidth.toFloat() / page.width.toFloat()
                                val targetHeight = (page.height * pageScale).toInt()
                                val bitmap = Bitmap.createBitmap(screenWidth, targetHeight, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                list.add(bitmap)
                            }
                        }
                        withContext(Dispatchers.Main) { pages = list }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        if (pages.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(pages) { pageBmp ->
                        Image(
                            bitmap = pageBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(4f) }) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = Color.White)
                }
                Text("${(scale * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    scale = (scale - 0.5f).coerceAtLeast(1f)
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                }) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = Color.White)
                }
                IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LocalHtmlViewer(uri: Uri) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, ByteArrayInputStream("External network blocked".toByteArray()))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    loadDataWithBaseURL("file:///android_asset/", content, "text/html", "UTF-8", null)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

suspend fun extractTextContent(
    context: Context,
    item: CourseItem,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val uri = Uri.parse(item.uriString)
    when (item.type) {
        FileType.HTML -> {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?.replace(Regex("<[^>]*>"), " ") ?: ""
        }
        FileType.PDF -> {
            "محتوى دراسي من ملف PDF: ${item.name}"
        }
        FileType.VIDEO, FileType.AUDIO -> {
            onProgress("استخراج المسار الصوتي...")
            val extractedAudio = if (item.type == FileType.VIDEO) extractAudioTrack(context, uri) else copyToCache(context, uri)
            if (extractedAudio != null && extractedAudio.exists()) {
                onProgress("التفريغ النصي عبر Whisper...")
                val prefs = getEncryptedPrefs(context)
                val apiKey = prefs.getString("api_key", "") ?: ""
                val transcript = transcribeAudioWithGroqWhisper(extractedAudio, apiKey)
                extractedAudio.delete()
                transcript
            } else {
                ""
            }
        }
        else -> ""
    }
}

fun copyToCache(context: Context, uri: Uri): File? {
    return try {
        val file = File(context.cacheDir, "temp_${System.currentTimeMillis()}.mp3")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file
    } catch (e: Exception) { null }
}

fun extractAudioTrack(context: Context, videoUri: Uri): File? {
    val outputFile = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.m4a")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    return try {
        context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) return null

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(512 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
            }
            outputFile
        }
    } catch (e: Exception) {
        null
    } finally {
        try { extractor.release() } catch (e: Exception) {}
        try { muxer?.stop(); muxer?.release() } catch (e: Exception) {}
    }
}

suspend fun transcribeAudioWithGroqWhisper(audioFile: File, apiKey: String): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext ""
    val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("model", "whisper-large-v3")
        .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
        .build()

    val request = Request.Builder()
        .url("https://api.groq.com/openai/v1/audio/transcriptions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(requestBody)
        .build()

    try {
        client.newCall(request).execute().use { res ->
            val str = res.body?.string() ?: ""
            if (res.isSuccessful) JSONObject(str).optString("text", "") else ""
        }
    } catch (e: Exception) { "" }
}

suspend fun runAiAnalysis(context: Context, contentText: String): CourseSummary? = withContext(Dispatchers.IO) {
    val prefs = getEncryptedPrefs(context)
    val apiKey = prefs.getString("api_key", "") ?: ""
    val provider = prefs.getString("provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name
    if (apiKey.isBlank()) return@withContext null

    val systemPrompt = """
        You are an expert tutor. Analyze this content:
        <course_content>
        $contentText
        </course_content>
        
        Respond ONLY with a JSON object in this format:
        {
          "overview": "Short overall summary in Arabic",
          "keyPoints": ["Point 1", "Point 2", "Point 3"],
          "questions": [
            {
              "question": "Question text in Arabic?",
              "options": ["Opt 1", "Opt 2", "Opt 3", "Opt 4"],
              "correctIndex": 0,
              "explanation": "Why correct in Arabic"
            }
          ]
        }
    """.trimIndent()

    val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    try {
        val (url, authHeader, payload) = when (AiProvider.valueOf(provider)) {
            AiProvider.GROQ -> Triple(
                "https://api.groq.com/openai/v1/chat/completions",
                "Bearer $apiKey",
                JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", systemPrompt) })
                    })
                }
            )
            AiProvider.OPENAI -> Triple(
                "https://api.openai.com/v1/chat/completions",
                "Bearer $apiKey",
                JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", systemPrompt) })
                    })
                }
            )
            AiProvider.OPENROUTER -> Triple(
                "https://openrouter.ai/api/v1/chat/completions",
                "Bearer $apiKey",
                JSONObject().apply {
                    put("model", "meta-llama/llama-3.3-70b-instruct")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", systemPrompt) })
                    })
                }
            )
            AiProvider.GEMINI -> Triple(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey",
                "",
                JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", systemPrompt) })
                            })
                        })
                    })
                }
            )
            AiProvider.ANTHROPIC -> Triple(
                "https://api.anthropic.com/v1/messages",
                apiKey,
                JSONObject().apply {
                    put("model", "claude-3-5-sonnet-20241022")
                    put("max_tokens", 2048)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", systemPrompt)
                        })
                    })
                }
            )
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        if (authHeader.isNotBlank()) requestBuilder.addHeader("Authorization", authHeader)
        if (AiProvider.valueOf(provider) == AiProvider.ANTHROPIC) {
            requestBuilder.addHeader("x-api-key", authHeader)
            requestBuilder.addHeader("anthropic-version", "2023-06-01")
        }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext null
            val root = JSONObject(body)

            val rawJsonText = when (AiProvider.valueOf(provider)) {
                AiProvider.GEMINI -> root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                AiProvider.ANTHROPIC -> root.getJSONArray("content").getJSONObject(0).getString("text")
                else -> root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }

            val cleaned = rawJsonText.substringAfter("{").substringBeforeLast("}")
            val parsed = JSONObject("{$cleaned}")

            val overview = parsed.optString("overview", "")
            val keyPoints = mutableListOf<String>()
            val kpArr = parsed.optJSONArray("keyPoints") ?: JSONArray()
            for (i in 0 until kpArr.length()) keyPoints.add(kpArr.getString(i))

            val questions = mutableListOf<QuizQuestion>()
            val qArr = parsed.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until qArr.length()) {
                val qObj = qArr.getJSONObject(i)
                val opts = mutableListOf<String>()
                val optArr = qObj.optJSONArray("options") ?: JSONArray()
                for (j in 0 until optArr.length()) opts.add(optArr.getString(j))
                questions.add(
                    QuizQuestion(
                        question = qObj.optString("question"),
                        options = opts,
                        correctIndex = qObj.optInt("correctIndex", 0),
                        explanation = qObj.optString("explanation")
                    )
                )
            }
            CourseSummary(overview, keyPoints, questions)
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun SummaryAndQuizScreen(summary: CourseSummary, onClose: () -> Unit) {
    var selectedAnswers by remember { mutableStateOf(mapOf<Int, Int>()) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                Text("الملخص والاختبار التفاعلي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("الملخص التنفيذي", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(summary.overview)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("المفاهيم الجوهرية:", fontWeight = FontWeight.Bold)
            summary.keyPoints.forEach { pt ->
                Text("• $pt", modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("اختبر معلوماتك:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        itemsIndexed(summary.questions) { qIndex, q ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${qIndex + 1}. ${q.question}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    q.options.forEachIndexed { optIndex, optText ->
                        val isSelected = selectedAnswers[qIndex] == optIndex
                        val hasAnswered = selectedAnswers.containsKey(qIndex)
                        val isCorrect = optIndex == q.correctIndex

                        val btnColor = when {
                            hasAnswered && isCorrect -> Color(0xFF2E7D32)
                            hasAnswered && isSelected && !isCorrect -> Color(0xFFC62828)
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }

                        Button(
                            onClick = {
                                if (!hasAnswered) selectedAnswers = selectedAnswers + (qIndex to optIndex)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text(optText, color = Color.White)
                        }
                    }
                    if (selectedAnswers.containsKey(qIndex)) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "التفسير: ${q.explanation}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "cf_secure_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

fun saveCourses(context: Context, courses: List<Course>) {
    val arr = JSONArray()
    courses.forEach {
        arr.put(JSONObject().apply {
            put("id", it.id)
            put("title", it.title)
            put("createdAt", it.createdAt)
        })
    }
    context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).edit().putString("courses", arr.toString()).apply()
}

fun loadCourses(context: Context): List<Course> {
    val raw = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).getString("courses", null) ?: return emptyList()
    val list = mutableListOf<Course>()
    val arr = JSONArray(raw)
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(Course(id = obj.getString("id"), title = obj.getString("title"), createdAt = obj.optLong("createdAt", 0L)))
    }
    return list
}

fun saveAllCourseItems(context: Context, items: List<CourseItem>) {
    val arr = JSONArray()
    items.forEach {
        arr.put(JSONObject().apply {
            put("id", it.id)
            put("courseId", it.courseId)
            put("name", it.name)
            put("uri", it.uriString)
            put("type", it.type.name)
            put("order", it.orderIndex)
            put("isCompleted", it.isCompleted)
        })
    }
    context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).edit().putString("course_items", arr.toString()).apply()
}

fun loadAllCourseItems(context: Context): List<CourseItem> {
    val raw = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).getString("course_items", null) ?: return emptyList()
    val list = mutableListOf<CourseItem>()
    val arr = JSONArray(raw)
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(
            CourseItem(
                id = obj.getString("id"),
                courseId = obj.optString("courseId", ""),
                name = obj.getString("name"),
                uriString = obj.getString("uri"),
                type = FileType.valueOf(obj.getString("type")),
                orderIndex = obj.getInt("order"),
                isCompleted = obj.optBoolean("isCompleted", false)
            )
        )
    }
    return list.sortedBy { it.orderIndex }
}

@Composable
fun AiConfigurationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = getEncryptedPrefs(context)
    var selectedProvider by remember { mutableStateOf(prefs.getString("provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات محرك الذكاء الاصطناعي (BYOK)") },
        text = {
            Column {
                Text("اختر المزود:", fontWeight = FontWeight.Bold)
                AiProvider.values().forEach { prov ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedProvider = prov.name }
                    ) {
                        RadioButton(selected = selectedProvider == prov.name, onClick = { selectedProvider = prov.name })
                        Text(prov.name)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("أدخل مفتاح الـ API") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                prefs.edit().putString("provider", selectedProvider).putString("api_key", apiKey).apply()
                onDismiss()
            }) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}