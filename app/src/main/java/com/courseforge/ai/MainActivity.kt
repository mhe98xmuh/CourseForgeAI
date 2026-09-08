package com.courseforge.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
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
import java.util.concurrent.TimeUnit

enum class FileType { PDF, VIDEO, AUDIO, HTML, UNKNOWN }
enum class AiProvider { GROQ, GEMINI, OPENAI, ANTHROPIC, OPENROUTER }

data class CourseItem(
    val id: String,
    val name: String,
    val uriString: String,
    val type: FileType,
    val orderIndex: Int
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
                    CourseForgeApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseForgeApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var items by remember { mutableStateOf(loadCourseItems(context)) }
    var selectedItem by remember { mutableStateOf<CourseItem?>(null) }
    var showAiSettings by remember { mutableStateOf(false) }
    var activeSummary by remember { mutableStateOf<CourseSummary?>(null) }
    var isProcessingAi by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val newEntries = uris.mapIndexed { idx, uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "مستند_${System.currentTimeMillis()}"
            val type = when {
                fileName.endsWith(".pdf", true) -> FileType.PDF
                fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".webm", true) -> FileType.VIDEO
                fileName.endsWith(".mp3", true) || fileName.endsWith(".wav", true) || fileName.endsWith(".m4a", true) -> FileType.AUDIO
                fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> FileType.HTML
                else -> FileType.UNKNOWN
            }
            CourseItem(
                id = uri.toString(),
                name = fileName,
                uriString = uri.toString(),
                type = type,
                orderIndex = items.size + idx
            )
        }
        items = items + newEntries
        saveCourseItems(context, items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CourseForge AI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAiSettings = true }) {
                        Icon(Icons.Default.Psychology, contentDescription = "AI Settings")
                    }
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Files")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (activeSummary != null) {
                SummaryAndQuizScreen(summary = activeSummary!!, onClose = { activeSummary = null })
            } else if (selectedItem != null) {
                ContentPlayerScreen(
                    item = selectedItem!!,
                    onBack = { selectedItem = null },
                    onAnalyze = {
                        coroutineScope.launch {
                            isProcessingAi = true
                            statusMessage = "جاري استخراج النص ومعالجة المحتوى..."
                            val extractedText = extractTextContent(context, selectedItem!!) { statusMessage = it }
                            if (extractedText.isBlank()) {
                                statusMessage = "تعذر استخراج نص من الملف المختار."
                                kotlinx.coroutines.delay(2000)
                            } else {
                                statusMessage = "جاري التحليل وتوليد الاختبار الذكي..."
                                val summary = runAiAnalysis(context, extractedText)
                                if (summary != null) {
                                    activeSummary = summary
                                } else {
                                    statusMessage = "فشل الاتصال بمزود الذكاء الاصطناعي."
                                    kotlinx.coroutines.delay(2500)
                                }
                            }
                            isProcessingAi = false
                            statusMessage = null
                        }
                    }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    itemsIndexed(items) { index, item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedItem = item },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
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
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(item.name, modifier = Modifier.weight(1f), maxLines = 1)

                                IconButton(onClick = {
                                    if (index > 0) {
                                        val mutable = items.toMutableList()
                                        val temp = mutable[index]
                                        mutable[index] = mutable[index - 1]
                                        mutable[index - 1] = temp
                                        items = mutable
                                        saveCourseItems(context, items)
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                                }
                                IconButton(onClick = {
                                    if (index < items.size - 1) {
                                        val mutable = items.toMutableList()
                                        val temp = mutable[index]
                                        mutable[index] = mutable[index + 1]
                                        mutable[index + 1] = temp
                                        items = mutable
                                        saveCourseItems(context, items)
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                                }
                            }
                        }
                    }
                }
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

            if (showAiSettings) {
                AiConfigurationDialog(onDismiss = { showAiSettings = false })
            }
        }
    }
}

@Composable
fun ContentPlayerScreen(item: CourseItem, onBack: () -> Unit, onAnalyze: () -> Unit) {
    val uri = Uri.parse(item.uriString)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            Button(onClick = onAnalyze) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("تحليل ذكي")
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (item.type) {
                FileType.VIDEO, FileType.AUDIO -> UniversalMediaPlayer(uri = uri)
                FileType.PDF -> NativePdfViewer(uri = uri)
                FileType.HTML -> SecureOfflineHtmlViewer(uri = uri)
                FileType.UNKNOWN -> Text("تنسيق غير مدعوم للمعاينة المباشرة", modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun UniversalMediaPlayer(uri: Uri) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun NativePdfViewer(uri: Uri) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val loaded = mutableListOf<Bitmap>()
                        val count = minOf(renderer.pageCount, 25)
                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                loaded.add(bmp)
                            }
                        }
                        withContext(Dispatchers.Main) { pages = loaded }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(pages) { pageBmp ->
                Image(
                    bitmap = pageBmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureOfflineHtmlViewer(uri: Uri) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Access Denied", null, ByteArrayInputStream("Offline View Only".toByteArray()))
                    }
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    loadDataWithBaseURL("about:blank", content, "text/html", "UTF-8", null)
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
            "محتوى مستند PDF: ${item.name}"
        }
        FileType.VIDEO, FileType.AUDIO -> {
            onProgress("استخراج المسار الصوتي...")
            val extractedAudio = if (item.type == FileType.VIDEO) extractAudioTrack(context, uri) else copyToCache(context, uri)
            if (extractedAudio != null && extractedAudio.exists()) {
                onProgress("التفريغ النصي الصوتي عبر Whisper...")
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
    } catch (_: Exception) { null }
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
    } catch (_: Exception) {
        null
    } finally {
        try { extractor.release() } catch (_: Exception) {}
        try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
    }
}

suspend fun transcribeAudioWithGroqWhisper(audioFile: File, apiKey: String): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext "مفتاح API غير متوفر للتفريغ النصي."
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
            if (res.isSuccessful) {
                JSONObject(str).optString("text", "")
            } else {
                ""
            }
        }
    } catch (_: Exception) { "" }
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
                buildOpenAiStylePayload("llama-3.3-70b-versatile", systemPrompt)
            )
            AiProvider.OPENAI -> Triple(
                "https://api.openai.com/v1/chat/completions",
                "Bearer $apiKey",
                buildOpenAiStylePayload("gpt-4o-mini", systemPrompt)
            )
            AiProvider.OPENROUTER -> Triple(
                "https://openrouter.ai/api/v1/chat/completions",
                "Bearer $apiKey",
                buildOpenAiStylePayload("meta-llama/llama-3.3-70b-instruct", systemPrompt)
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

        if (authHeader.isNotBlank()) {
            requestBuilder.addHeader("Authorization", authHeader)
        }
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
    } catch (_: Exception) {
        null
    }
}

fun buildOpenAiStylePayload(model: String, prompt: String): JSONObject {
    return JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
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
                                if (!hasAnswered) {
                                    selectedAnswers = selectedAnswers + (qIndex to optIndex)
                                }
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

fun getEncryptedPrefs(context: Context) = EncryptedSharedPreferences.create(
    "cf_secure_keys",
    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
    context,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

fun saveCourseItems(context: Context, items: List<CourseItem>) {
    val arr = JSONArray()
    items.forEach {
        arr.put(JSONObject().apply {
            put("id", it.id)
            put("name", it.name)
            put("uri", it.uriString)
            put("type", it.type.name)
            put("order", it.orderIndex)
        })
    }
    context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
}

fun loadCourseItems(context: Context): List<CourseItem> {
    val raw = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE).getString("items", null) ?: return emptyList()
    val list = mutableListOf<CourseItem>()
    val arr = JSONArray(raw)
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(
            CourseItem(
                id = obj.getString("id"),
                name = obj.getString("name"),
                uriString = obj.getString("uri"),
                type = FileType.valueOf(obj.getString("type")),
                orderIndex = obj.getInt("order")
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
            }) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}ext "مفتاح API غير متوفر لل
