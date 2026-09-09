package com.courseforge.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.courseforge.ai.player.*
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class FileType { PDF, VIDEO, AUDIO, HTML, UNKNOWN }
enum class AiProvider { GROQ, GEMINI, OPENAI, ANTHROPIC, OPENROUTER }

data class Course(val id: String, val title: String, val createdAt: Long = System.currentTimeMillis())
data class CourseItem(val id: String, val courseId: String, val name: String, val uriString: String, val type: FileType, val orderIndex: Int, val isCompleted: Boolean = false)
data class QuizQuestion(val question: String, val options: List<String>, val correctIndex: Int, val explanation: String)
data class CourseSummary(val overview: String, val keyPoints: List<String>, val questions: List<QuizQuestion>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            resetFullscreen(context)
        } else if (activeCourse != null) {
            activeCourse = null
        }
    }

    if (selectedItem != null) {
        ContentPlayerScreen(
            item = selectedItem!!,
            onBack = {
                selectedItem = null
                resetFullscreen(context)
            }
        )
    } else if (activeCourse != null) {
        CourseDetailScreen(
            course = activeCourse!!,
            allItems = allItems,
            onUpdateItems = { updated -> allItems = updated; saveAllCourseItems(context, updated) },
            onOpenItem = { item -> selectedItem = item },
            onBack = { activeCourse = null }
        )
    } else {
        CoursesListScreen(
            courses = courses,
            allItems = allItems,
            onSelectCourse = { activeCourse = it },
            onCreateCourse = { name ->
                courses = courses + Course(id = UUID.randomUUID().toString(), title = name)
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

    if (showAiSettings) AiConfigurationDialog(onDismiss = { showAiSettings = false })
}

fun resetFullscreen(context: Context) {
    val act = context as? Activity
    act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    act?.window?.let { window ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = params
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesListScreen(
    courses: List<Course>, allItems: List<CourseItem>, onSelectCourse: (Course) -> Unit,
    onCreateCourse: (String) -> Unit, onDeleteCourse: (Course) -> Unit, onOpenSettings: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CourseForge AI v2.0.0", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "New") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(courses.size) { index ->
                    val course = courses[index]
                    val itemsForCourse = allItems.filter { it.courseId == course.id }
                    val total = itemsForCourse.size
                    val completed = itemsForCourse.count { it.isCompleted }
                    val progress = if (total > 0) completed.toFloat() / total else 0f

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onSelectCourse(course) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(course.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDeleteCourse(course) }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(6.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("${(progress * 100).toInt()}% ($completed/$total)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text("إنشاء دورة") },
                    text = { OutlinedTextField(value = newCourseName, onValueChange = { newCourseName = it }, label = { Text("الاسم") }, singleLine = true) },
                    confirmButton = { Button(onClick = { if (newCourseName.isNotBlank()) { onCreateCourse(newCourseName.trim()); newCourseName = ""; showCreateDialog = false } }) { Text("حفظ") } },
                    dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("إلغاء") } }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    course: Course, allItems: List<CourseItem>, onUpdateItems: (List<CourseItem>) -> Unit,
    onOpenItem: (CourseItem) -> Unit, onBack: () -> Unit
) {
    val context = LocalContext.current
    val courseItems = remember(allItems, course.id) { allItems.filter { it.courseId == course.id }.sortedBy { it.orderIndex } }
    val total = courseItems.size
    val completed = courseItems.count { it.isCompleted }
    val progress = if (total > 0) completed.toFloat() / total else 0f

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val newEntries = uris.mapIndexed { idx, uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "مستند_${System.currentTimeMillis()}"
            val type = when {
                fileName.endsWith(".pdf", true) -> FileType.PDF
                fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) -> FileType.VIDEO
                fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) -> FileType.AUDIO
                fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> FileType.HTML
                else -> FileType.UNKNOWN
            }
            CourseItem(id = UUID.randomUUID().toString(), courseId = course.id, name = fileName, uriString = uri.toString(), type = type, orderIndex = courseItems.size + idx)
        }
        onUpdateItems(allItems + newEntries)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course.title, fontWeight = FontWeight.Bold, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, contentDescription = "Add") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row {
                        Text("نسبة الإكمال", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${(progress * 100).toInt()}% ($completed/$total)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(courseItems) { index, item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenItem(item) }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.isCompleted, onCheckedChange = { checked ->
                                onUpdateItems(allItems.map { if (it.id == item.id) it.copy(isCompleted = checked) else it })
                            })
                            when (item.type) {
                                FileType.PDF -> Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF", tint = Color.Red)
                                FileType.HTML -> Icon(Icons.Default.Code, contentDescription = "HTML", tint = Color(0xFFE65100))
                                FileType.VIDEO -> Icon(Icons.Default.VideoLibrary, contentDescription = "Video", tint = MaterialTheme.colorScheme.primary)
                                FileType.AUDIO -> Icon(Icons.Default.AudioFile, contentDescription = "Audio", tint = MaterialTheme.colorScheme.primary)
                                else -> Icon(Icons.Default.InsertDriveFile, contentDescription = "Unknown")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(item.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onUpdateItems(allItems.filterNot { it.id == item.id }) }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
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
    
    // مراقبة الدوران الحقيقي للهاتف
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    var isFullscreen by remember { mutableStateOf(isLandscape) }
    var activeSummary by remember { mutableStateOf<CourseSummary?>(null) }
    var isProcessingAi by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // مزامنة حالة التطبيق مع الدوران الفعلي (إخفاء تلقائي وحاسم للحواف)
    LaunchedEffect(isLandscape) {
        isFullscreen = isLandscape
        val act = context as? Activity
        val window = act?.window
        if (isLandscape) {
            window?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val params = it.attributes
                    params.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    it.attributes = params
                }
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowInsetsControllerCompat(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            resetFullscreen(context)
        }
    }

    // زر الرجوع في الوضع الأفقي يعيدك للوضع العمودي أولاً
    BackHandler(enabled = isLandscape || activeSummary != null) {
        if (activeSummary != null) {
            activeSummary = null
        } else if (isLandscape) {
            val act = context as? Activity
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullscreen) {
                // إضافة مسافة آمنة في الوضع العمودي لمنع التداخل مع شريط الإشعارات
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp), 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                    Text(item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        coroutineScope.launch {
                            isProcessingAi = true
                            statusMessage = "جاري استخراج النص والتحليل..."
                            val extracted = extractTextContent(context, item)
                            if (extracted.isNotBlank()) {
                                activeSummary = runAiAnalysis(context, extracted)
                            }
                            isProcessingAi = false
                        }
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("شرح واختبار")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (item.type) {
                    FileType.VIDEO -> VideoEngine(
                        uri = Uri.parse(item.uriString),
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = {
                            val act = context as? Activity
                            if (isLandscape) {
                                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                            } else {
                                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    )
                    FileType.AUDIO -> UniversalAudioPlayer(uri = Uri.parse(item.uriString))
                    FileType.PDF -> PdfEngine(uri = Uri.parse(item.uriString), fileId = item.id)
                    FileType.HTML -> HtmlEngine(uri = Uri.parse(item.uriString))
                    else -> Text("تنسيق غير مدعوم", modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        if (isProcessingAi) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(statusMessage ?: "", color = Color.White)
                }
            }
        }
    }

    if (activeSummary != null) {
        Dialog(
            onDismissRequest = { activeSummary = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 8.dp
            ) {
                SummaryAndQuizScreen(summary = activeSummary!!, onClose = { activeSummary = null })
            }
        }
    }
}

@Composable
fun UniversalAudioPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxWidth().height(260.dp))
    }
}

suspend fun extractTextContent(context: Context, item: CourseItem): String = withContext(Dispatchers.IO) {
    val uri = Uri.parse(item.uriString)
    when (item.type) {
        FileType.HTML -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.replace(Regex("<[^>]*>"), " ") ?: ""
        FileType.PDF -> "محتوى PDF: ${item.name}"
        FileType.VIDEO, FileType.AUDIO -> {
            val audio = if (item.type == FileType.VIDEO) extractAudioTrack(context, uri) else File(context.cacheDir, "temp.mp3").apply {
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(this).use { output -> input.copyTo(output) } }
            }
            if (audio != null && audio.exists()) {
                val transcript = transcribeAudioWithGroqWhisper(audio, getEncryptedPrefs(context).getString("api_key", "") ?: "")
                audio.delete()
                transcript
            } else ""
        }
        else -> ""
    }
}

fun extractAudioTrack(context: Context, videoUri: Uri): File? {
    val outputFile = File(context.cacheDir, "ext_${System.currentTimeMillis()}.m4a")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    return try {
        context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { audioTrackIndex = i; format = f; break }
            }
            val finalFormat = format
            if (audioTrackIndex == -1 || finalFormat == null) return null
            extractor.selectTrack(audioTrackIndex)
            val safeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = safeMuxer
            val muxerTrack = safeMuxer.addTrack(finalFormat)
            safeMuxer.start()
            val buffer = ByteBuffer.allocate(512 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                safeMuxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
            }
            outputFile
        }
    } catch (e: Exception) { null } finally {
        try { extractor.release() } catch (e: Exception) {}
        try { muxer?.stop(); muxer?.release() } catch (e: Exception) {}
    }
}

suspend fun transcribeAudioWithGroqWhisper(audioFile: File, apiKey: String): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext ""
    try {
        OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").addHeader("Authorization", "Bearer $apiKey")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", "whisper-large-v3").addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType())).build()).build()
        ).execute().use { res -> if (res.isSuccessful) JSONObject(res.body?.string() ?: "").optString("text", "") else "" }
    } catch (e: Exception) { "" }
}

suspend fun runAiAnalysis(context: Context, contentText: String): CourseSummary? = withContext(Dispatchers.IO) {
    val prefs = getEncryptedPrefs(context)
    val apiKey = prefs.getString("api_key", "") ?: return@withContext null
    val provider = prefs.getString("provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name
    val prompt = "You are a tutor. Analyze this content:\n<content>$contentText</content>\nRespond ONLY with JSON format: {\"overview\":\"Arabic summary\",\"keyPoints\":[\"Point\"],\"questions\":[{\"question\":\"Q in Arabic?\",\"options\":[\"Opt1\",\"Opt2\",\"Opt3\",\"Opt4\"],\"correctIndex\":0,\"explanation\":\"Why in Arabic\"}]}"
    
    try {
        val (url, authHeader, payload) = when (AiProvider.valueOf(provider)) {
            AiProvider.GROQ -> Triple("https://api.groq.com/openai/v1/chat/completions", "Bearer $apiKey", JSONObject().apply { put("model", "llama-3.3-70b-versatile"); put("messages", JSONArray().apply { put(JSONObject().apply { put("role", "user"); put("content", prompt) }) }) })
            AiProvider.OPENAI -> Triple("https://api.openai.com/v1/chat/completions", "Bearer $apiKey", JSONObject().apply { put("model", "gpt-4o-mini"); put("messages", JSONArray().apply { put(JSONObject().apply { put("role", "user"); put("content", prompt) }) }) })
            else -> Triple("https://api.groq.com/openai/v1/chat/completions", "Bearer $apiKey", JSONObject().apply { put("model", "llama-3.3-70b-versatile"); put("messages", JSONArray().apply { put(JSONObject().apply { put("role", "user"); put("content", prompt) }) }) })
        }
        OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType())).addHeader("Authorization", authHeader).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val raw = JSONObject(resp.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val parsed = JSONObject("{${raw.substringAfter("{").substringBeforeLast("}")}}")
            val kp = mutableListOf<String>(); val kpArr = parsed.optJSONArray("keyPoints") ?: JSONArray(); for (i in 0 until kpArr.length()) kp.add(kpArr.getString(i))
            val qs = mutableListOf<QuizQuestion>(); val qArr = parsed.optJSONArray("questions") ?: JSONArray(); for (i in 0 until qArr.length()) { val qObj = qArr.getJSONObject(i); val opts = mutableListOf<String>(); val optArr = qObj.optJSONArray("options") ?: JSONArray(); for (j in 0 until optArr.length()) opts.add(optArr.getString(j)); qs.add(QuizQuestion(qObj.optString("question"), opts, qObj.optInt("correctIndex", 0), qObj.optString("explanation"))) }
            CourseSummary(parsed.optString("overview", ""), kp, qs)
        }
    } catch (e: Exception) { null }
}

@Composable
fun SummaryAndQuizScreen(summary: CourseSummary, onClose: () -> Unit) {
    var selectedAnswers by remember { mutableStateOf(mapOf<Int, Int>()) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }; Text("الشرح والاختبار التفاعلي", fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(modifier = Modifier.padding(16.dp)) { Text("الشرح والمفاهيم", fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(6.dp)); Text(summary.overview) } }
            Spacer(modifier = Modifier.height(12.dp))
            Text("المحاور الجوهرية:", fontWeight = FontWeight.Bold)
            summary.keyPoints.forEach { pt -> Text("• $pt", modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp)) }
            Spacer(modifier = Modifier.height(20.dp))
            Text("اختبر معلوماتك:", fontWeight = FontWeight.Bold)
        }
        itemsIndexed(summary.questions) { qIndex, q ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${qIndex + 1}. ${q.question}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    q.options.forEachIndexed { optIndex, optText ->
                        val isSelected = selectedAnswers[qIndex] == optIndex
                        val hasAnswered = selectedAnswers.containsKey(qIndex)
                        val btnColor = when { hasAnswered && optIndex == q.correctIndex -> Color(0xFF2E7D32); hasAnswered && isSelected -> Color(0xFFC62828); isSelected -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant }
                        Button(onClick = { if (!hasAnswered) selectedAnswers = selectedAnswers + (qIndex to optIndex) }, colors = ButtonDefaults.buttonColors(containerColor = btnColor), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(optText, color = Color.White) }
                    }
                    if (selectedAnswers.containsKey(qIndex)) { Spacer(modifier = Modifier.height(6.dp)); Text("التفسير: ${q.explanation}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

fun getEncryptedPrefs(context: Context): android.content.SharedPreferences = EncryptedSharedPreferences.create(context, "cf_secure_keys", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
fun saveCourses(context: Context, courses: List<Course>) { val arr = JSONArray(); courses.forEach { arr.put(JSONObject().apply { put("id", it.id); put("title", it.title); put("createdAt", it.createdAt) }) }; context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).edit().putString("courses", arr.toString()).apply() }
fun loadCourses(context: Context): List<Course> { val raw = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).getString("courses", null) ?: return emptyList(); val list = mutableListOf<Course>(); val arr = JSONArray(raw); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); list.add(Course(id = obj.getString("id"), title = obj.getString("title"), createdAt = obj.optLong("createdAt", 0L))) }; return list }
fun saveAllCourseItems(context: Context, items: List<CourseItem>) { val arr = JSONArray(); items.forEach { arr.put(JSONObject().apply { put("id", it.id); put("courseId", it.courseId); put("name", it.name); put("uri", it.uriString); put("type", it.type.name); put("order", it.orderIndex); put("isCompleted", it.isCompleted) }) }; context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).edit().putString("course_items", arr.toString()).apply() }
fun loadAllCourseItems(context: Context): List<CourseItem> { val raw = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).getString("course_items", null) ?: return emptyList(); val list = mutableListOf<CourseItem>(); val arr = JSONArray(raw); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); list.add(CourseItem(id = obj.getString("id"), courseId = obj.optString("courseId", ""), name = obj.getString("name"), uriString = obj.getString("uri"), type = FileType.valueOf(obj.getString("type")), orderIndex = obj.getInt("order"), isCompleted = obj.optBoolean("isCompleted", false))) }; return list.sortedBy { it.orderIndex } }

@Composable
fun AiConfigurationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = getEncryptedPrefs(context)
    var selectedProvider by remember { mutableStateOf(prefs.getString("provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("إعدادات محرك الذكاء الاصطناعي") },
        text = { Column { AiProvider.values().forEach { prov -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedProvider = prov.name }) { RadioButton(selected = selectedProvider == prov.name, onClick = { selectedProvider = prov.name }); Text(prov.name) } }; Spacer(modifier = Modifier.height(10.dp)); OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("أدخل مفتاح الـ API") }, singleLine = true) } },
        confirmButton = { Button(onClick = { prefs.edit().putString("provider", selectedProvider).putString("api_key", apiKey).apply(); onDismiss() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}