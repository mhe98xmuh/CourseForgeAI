package com.courseforge.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class ContentType {
    VIDEO, AUDIO, PDF, HTML, UNKNOWN
}

data class Course(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class CourseItem(
    val id: String = UUID.randomUUID().toString(),
    val courseId: String,
    val title: String,
    val uriString: String,
    val contentType: ContentType,
    val displayOrder: Int,
    val lastPlaybackPositionMs: Long = 0L,
    val isCompleted: Boolean = false
)

class CourseLocalRepository(context: Context) {
    private val prefs = context.getSharedPreferences("courseforge_database_v5", Context.MODE_PRIVATE)

    fun loadCourses(): List<Course> {
        val rawJson = prefs.getString("courses_list", null) ?: return emptyList()
        val list = mutableListOf<Course>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Course(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveCourses(courses: List<Course>) {
        val array = JSONArray()
        courses.forEach { c ->
            array.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("createdAt", c.createdAt)
                }
            )
        }
        prefs.edit().putString("courses_list", array.toString()).apply()
    }

    fun loadItems(): List<CourseItem> {
        val rawJson = prefs.getString("course_items_v5", null) ?: return emptyList()
        val items = mutableListOf<CourseItem>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    CourseItem(
                        id = obj.getString("id"),
                        courseId = obj.optString("courseId", ""),
                        title = obj.getString("title"),
                        uriString = obj.getString("uriString"),
                        contentType = ContentType.valueOf(obj.getString("contentType")),
                        displayOrder = obj.getInt("displayOrder"),
                        lastPlaybackPositionMs = obj.optLong("lastPlaybackPositionMs", 0L),
                        isCompleted = obj.optBoolean("isCompleted", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return items.sortedBy { it.displayOrder }
    }

    fun saveItems(items: List<CourseItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("courseId", item.courseId)
                    put("title", item.title)
                    put("uriString", item.uriString)
                    put("contentType", item.contentType.name)
                    put("displayOrder", item.displayOrder)
                    put("lastPlaybackPositionMs", item.lastPlaybackPositionMs)
                    put("isCompleted", item.isCompleted)
                }
            )
        }
        prefs.edit().putString("course_items_v5", array.toString()).apply()
    }

    fun updatePlaybackPosition(itemId: String, positionMs: Long) {
        val items = loadItems().map {
            if (it.id == itemId) it.copy(lastPlaybackPositionMs = positionMs) else it
        }
        saveItems(items)
    }

    fun exportToJson(): String {
        val root = JSONObject()
        root.put("courses", JSONArray(prefs.getString("courses_list", "[]")))
        root.put("items", JSONArray(prefs.getString("course_items_v5", "[]")))
        return root.toString(2)
    }

    fun importFromJson(jsonStr: String): Boolean {
        try {
            val root = JSONObject(jsonStr)
            if (root.has("courses") && root.has("items")) {
                prefs.edit()
                    .putString("courses_list", root.getJSONArray("courses").toString())
                    .putString("course_items_v5", root.getJSONArray("items").toString())
                    .apply()
                return true
            }
        } catch (_: Exception) {}
        return false
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureOfflineHtmlViewer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    htmlContent = stream.bufferedReader(StandardCharsets.UTF_8).readText()
                } ?: run { errorMsg = "تعذر قراءة ملف HTML" }
            } catch (e: Exception) {
                errorMsg = "خطأ: ${e.localizedMessage}"
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            errorMsg != null -> Text(text = errorMsg ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
            htmlContent != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.apply {
                                javaScriptEnabled = true
                                blockNetworkLoads = true
                                allowFileAccess = false
                                allowContentAccess = false
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                defaultTextEncodingName = "utf-8"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val url = request?.url?.toString() ?: ""
                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null, null)
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }
                            loadDataWithBaseURL("about:blank", htmlContent!!, "text/html", "UTF-8", null)
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
            else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun UniversalMediaPlayer(
    uri: Uri,
    title: String,
    isVideo: Boolean,
    initialPos: Long,
    modifier: Modifier = Modifier,
    onPositionChanged: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            seekTo(initialPos)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableLongStateOf(initialPos) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = exoPlayer.duration.coerceAtLeast(0L)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onPositionChanged(exoPlayer.currentPosition)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (kotlinx.coroutines.isActive && isPlaying) {
            currentPos = exoPlayer.currentPosition
            onPositionChanged(currentPos)
            kotlinx.coroutines.delay(500)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("المحاضرة الصوتية", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier.size(160.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(80.dp))
                }
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = if (duration > 0) currentPos.toFloat() / duration else 0f,
                        onValueChange = { frac ->
                            val target = (frac * duration).toLong()
                            exoPlayer.seekTo(target)
                            currentPos = target
                        }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(currentPos), color = Color.LightGray, fontSize = 12.sp)
                        Text(formatDuration(duration), color = Color.LightGray, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        speed = when (speed) {
                            1f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2f
                            else -> 1f
                        }
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                    }) {
                        Text("${speed}x", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }

                    IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "تراجع", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    FloatingActionButton(
                        onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        shape = CircleShape
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    }

                    IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration)) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "تقديم", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, mins, secs) else String.format("%02d:%02d", mins, secs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesListScreen(
    courses: List<Course>,
    allItems: List<CourseItem>,
    onSelectCourse: (Course) -> Unit,
    onCreateCourse: (String) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CourseForge AI - الدورات التعليمية", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onExportBackup) {
                        Icon(Icons.Default.Upload, contentDescription = "تصدير نسخة احتياطية")
                    }
                    IconButton(onClick = onImportBackup) {
                        Icon(Icons.Default.Download, contentDescription = "استعادة نسخة احتياطية")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "إنشاء دورة جديدة")
            }
        }
    ) { padding ->
        if (courses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("لا توجد دورات مسجلة بعد", fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text("اضغط على زر (+) لإنشاء دورتك الأولى أو استعد من النسخة الاحتياطية", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(courses) { _, course ->
                    val courseItems = allItems.filter { it.courseId == course.id }
                    val total = courseItems.size
                    val completed = courseItems.count { it.isCompleted }
                    val progress = if (total > 0) completed.toFloat() / total else 0f

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCourse(course) },
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(course.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDeleteCourse(course) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "حذف الدورة", tint = Color.Red)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("${(progress * 100).toInt()}% ($completed/$total)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("إنشاء دورة تعليمية جديدة") },
                text = {
                    OutlinedTextField(
                        value = newCourseName,
                        onValueChange = { newCourseName = it },
                        label = { Text("اسم الدورة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newCourseName.isNotBlank()) {
                            onCreateCourse(newCourseName.trim())
                            newCourseName = ""
                            showCreateDialog = false
                        }
                    }) { Text("إنشاء") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("إلغاء") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSyllabusScreen(
    course: Course,
    items: List<CourseItem>,
    onBack: () -> Unit,
    onAddItemUris: (List<Uri>) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Unit,
    onDeleteItem: (CourseItem) -> Unit,
    onOpenItem: (CourseItem) -> Unit,
    onToggleCompleted: (CourseItem, Boolean) -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddItemUris(uris)
    }

    val total = items.size
    val completed = items.count { it.isCompleted }
    val progress = if (total > 0) completed.toFloat() / total else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "video/*",
                            "audio/*",
                            "application/pdf",
                            "text/html"
                        ))
                    }
                    try {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    } catch (_: Exception) {}
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة محاضرات")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("نسبة الإكمال", fontWeight = FontWeight.Bold)
                        Text("${(progress * 100).toInt()}% ($completed/$total)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("لا توجد محاضرات في هذه الدورة", fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text("اضغط على زر (+) لاختيار الفيديوهات والملفات من أي مجلد في هاتفك", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenItem(item) },
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.isCompleted,
                                    onCheckedChange = { checked -> onToggleCompleted(item, checked) }
                                )

                                val icon = when (item.contentType) {
                                    ContentType.VIDEO -> Icons.Default.Movie
                                    ContentType.AUDIO -> Icons.Default.Audiotrack
                                    ContentType.PDF -> Icons.Default.PictureAsPdf
                                    ContentType.HTML -> Icons.Default.Language
                                    ContentType.UNKNOWN -> Icons.Default.InsertDriveFile
                                }
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = when (item.contentType) {
                                            ContentType.VIDEO -> "فيديو"
                                            ContentType.AUDIO -> "تسجيل صوتي"
                                            ContentType.PDF -> "وثيقة PDF"
                                            ContentType.HTML -> "صفحة HTML"
                                            ContentType.UNKNOWN -> "ملف"
                                        },
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (index > 0) onMoveItem(index, index - 1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "للأعلى", modifier = Modifier.size(16.dp))
                                    }

                                    IconButton(
                                        onClick = { if (index < items.size - 1) onMoveItem(index, index + 1) },
                                        enabled = index < items.size - 1,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "للأسفل", modifier = Modifier.size(16.dp))
                                    }

                                    IconButton(onClick = { onDeleteItem(item) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = CourseLocalRepository(this)
        setContent {
            MaterialTheme {
                MainAppHost(repository = repository)
            }
        }
    }
}

@Composable
fun MainAppHost(repository: CourseLocalRepository) {
    val context = LocalContext.current

    var courses by remember { mutableStateOf(repository.loadCourses()) }
    var allItems by remember { mutableStateOf(repository.loadItems()) }
    var activeCourse by remember { mutableStateOf<Course?>(null) }
    var activeItemForViewing by remember { mutableStateOf<CourseItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(repository.exportToJson().toByteArray(StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {}
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val jsonStr = input.bufferedReader(StandardCharsets.UTF_8).readText()
                    if (repository.importFromJson(jsonStr)) {
                        courses = repository.loadCourses()
                        allItems = repository.loadItems()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = activeItemForViewing != null || activeCourse != null) {
        when {
            activeItemForViewing != null -> activeItemForViewing = null
            activeCourse != null -> activeCourse = null
        }
    }

    when {
        activeItemForViewing != null -> {
            val item = activeItemForViewing!!
            val uri = Uri.parse(item.uriString)

            Box(modifier = Modifier.fillMaxSize()) {
                when (item.contentType) {
                    ContentType.VIDEO -> UniversalMediaPlayer(
                        uri = uri,
                        title = item.title,
                        isVideo = true,
                        initialPos = item.lastPlaybackPositionMs,
                        onPositionChanged = { repository.updatePlaybackPosition(item.id, it) }
                    )
                    ContentType.AUDIO -> UniversalMediaPlayer(
                        uri = uri,
                        title = item.title,
                        isVideo = false,
                        initialPos = item.lastPlaybackPositionMs,
                        onPositionChanged = { repository.updatePlaybackPosition(item.id, it) }
                    )
                    ContentType.PDF -> {
                        LaunchedEffect(uri) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            activeItemForViewing = null
                        }
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    ContentType.HTML -> SecureOfflineHtmlViewer(uri = uri)
                    ContentType.UNKNOWN -> Text("صيغة غير مدعومة", modifier = Modifier.align(Alignment.Center))
                }

                if (item.contentType != ContentType.PDF) {
                    IconButton(
                        onClick = { activeItemForViewing = null },
                        modifier = Modifier.padding(16.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                }
            }
        }
        activeCourse != null -> {
            val currentCourse = activeCourse!!
            val courseItems = allItems.filter { it.courseId == currentCourse.id }

            CourseSyllabusScreen(
                course = currentCourse,
                items = courseItems,
                onBack = { activeCourse = null },
                onAddItemUris = { uris ->
                    val newItems = mutableListOf<CourseItem>()
                    uris.forEach { uri ->
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {}

                        val mime = context.contentResolver.getType(uri) ?: ""
                        val type = when {
                            mime.startsWith("video/") -> ContentType.VIDEO
                            mime.startsWith("audio/") -> ContentType.AUDIO
                            mime == "application/pdf" -> ContentType.PDF
                            mime == "text/html" -> ContentType.HTML
                            else -> {
                                val path = uri.toString().lowercase()
                                when {
                                    path.endsWith(".mp4") || path.endsWith(".mkv") -> ContentType.VIDEO
                                    path.endsWith(".mp3") || path.endsWith(".m4a") -> ContentType.AUDIO
                                    path.endsWith(".pdf") -> ContentType.PDF
                                    path.endsWith(".html") || path.endsWith(".htm") -> ContentType.HTML
                                    else -> ContentType.UNKNOWN
                                }
                            }
                        }

                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "محاضرة جديدة"
                        newItems.add(
                            CourseItem(
                                courseId = currentCourse.id,
                                title = name,
                                uriString = uri.toString(),
                                contentType = type,
                                displayOrder = courseItems.size + newItems.size
                            )
                        )
                    }
                    val updated = allItems + newItems
                    allItems = updated
                    repository.saveItems(updated)
                },
                onMoveItem = { from, to ->
                    val courseOnly = courseItems.toMutableList()
                    val item = courseOnly.removeAt(from)
                    courseOnly.add(to, item)
                    val updatedCourseOnly = courseOnly.mapIndexed { idx, it -> it.copy(displayOrder = idx) }
                    val otherItems = allItems.filter { it.courseId != currentCourse.id }
                    val updatedAll = otherItems + updatedCourseOnly
                    allItems = updatedAll
                    repository.saveItems(updatedAll)
                },
                onDeleteItem = { item ->
                    val updated = allItems.filterNot { it.id == item.id }
                    allItems = updated
                    repository.saveItems(updated)
                },
                onOpenItem = { activeItemForViewing = it },
                onToggleCompleted = { item, completed ->
                    val updated = allItems.map { if (it.id == item.id) it.copy(isCompleted = completed) else it }
                    allItems = updated
                    repository.saveItems(updated)
                }
            )
        }
        else -> {
            CoursesListScreen(
                courses = courses,
                allItems = allItems,
                onSelectCourse = { activeCourse = it },
                onCreateCourse = { name ->
                    val newCourse = Course(title = name)
                    val updated = courses + newCourse
                    courses = updated
                    repository.saveCourses(updated)
                },
                onDeleteCourse = { course ->
                    val updatedCourses = courses.filterNot { it.id == course.id }
                    val updatedItems = allItems.filterNot { it.courseId == course.id }
                    courses = updatedCourses
                    allItems = updatedItems
                    repository.saveCourses(updatedCourses)
                    repository.saveItems(updatedItems)
                },
                onExportBackup = {
                    exportLauncher.launch("courseforge_backup_${System.currentTimeMillis()}.json")
                },
                onImportBackup = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            )
        }
    }
}