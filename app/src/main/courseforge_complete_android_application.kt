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
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class ContentType {
    VIDEO, AUDIO, PDF, HTML, UNKNOWN
}

enum class SupportedAiProvider(val displayName: String) {
    GEMINI("Google Gemini"),
    GROQ("Groq (Llama 3 / Whisper)"),
    OPENAI("OpenAI (ChatGPT)"),
    ANTHROPIC("Anthropic (Claude)"),
    OPENROUTER("OpenRouter")
}

data class QuizQuestion(
    val id: Int,
    val question: String,
    val options: List<String>,
    val correctAnswerIndex: Int,
    val explanation: String
)

data class LectureSummary(
    val title: String,
    val executiveSummary: String,
    val keyPoints: List<String>,
    val coreConcepts: List<String>
)

data class CourseItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val uriString: String,
    val contentType: ContentType,
    val displayOrder: Int,
    val lastPlaybackPositionMs: Long = 0L,
    val isCompleted: Boolean = false,
    val extractedTranscript: String? = null,
    val summaryJson: String? = null,
    val quizJson: String? = null
)

class KeystoreSecurityManager(context: Context) {
    private val securePrefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "courseforge_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("courseforge_fallback_vault", Context.MODE_PRIVATE)
        }
    }

    fun saveApiKey(provider: SupportedAiProvider, apiKey: String) {
        securePrefs.edit().putString("key_${provider.name}", apiKey.trim()).apply()
    }

    fun getApiKey(provider: SupportedAiProvider): String? {
        return securePrefs.getString("key_${provider.name}", null)?.takeIf { it.isNotBlank() }
    }

    fun hasKeyFor(provider: SupportedAiProvider): Boolean {
        return !getApiKey(provider).isNullOrEmpty()
    }
}

class CourseLocalRepository(context: Context) {
    private val prefs = context.getSharedPreferences("courseforge_database", Context.MODE_PRIVATE)

    fun loadItems(): List<CourseItem> {
        val rawJson = prefs.getString("course_items_v1", null) ?: return emptyList()
        val items = mutableListOf<CourseItem>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    CourseItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        uriString = obj.getString("uriString"),
                        contentType = ContentType.valueOf(obj.getString("contentType")),
                        displayOrder = obj.getInt("displayOrder"),
                        lastPlaybackPositionMs = obj.optLong("lastPlaybackPositionMs", 0L),
                        isCompleted = obj.optBoolean("isCompleted", false),
                        extractedTranscript = obj.optString("extractedTranscript", null),
                        summaryJson = obj.optString("summaryJson", null),
                        quizJson = obj.optString("quizJson", null)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items.sortedBy { it.displayOrder }
    }

    fun saveItems(items: List<CourseItem>) {
        val array = JSONArray()
        items.forEachIndexed { index, item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uriString", item.uriString)
                put("contentType", item.contentType.name)
                put("displayOrder", index)
                put("lastPlaybackPositionMs", item.lastPlaybackPositionMs)
                put("isCompleted", item.isCompleted)
                item.extractedTranscript?.let { put("extractedTranscript", it) }
                item.summaryJson?.let { put("summaryJson", it) }
                item.quizJson?.let { put("quizJson", it) }
            }
            array.put(obj)
        }
        prefs.edit().putString("course_items_v1", array.toString()).apply()
    }

    fun updatePlaybackPosition(itemId: String, positionMs: Long) {
        val items = loadItems().map {
            if (it.id == itemId) it.copy(lastPlaybackPositionMs = positionMs) else it
        }
        saveItems(items)
    }

    fun updateAiArtifacts(itemId: String, transcript: String?, summaryJson: String?, quizJson: String?) {
        val items = loadItems().map {
            if (it.id == itemId) {
                it.copy(
                    extractedTranscript = transcript ?: it.extractedTranscript,
                    summaryJson = summaryJson ?: it.summaryJson,
                    quizJson = quizJson ?: it.quizJson
                )
            } else it
        }
        saveItems(items)
    }
}

object AudioTrackExtractor {
    suspend fun extractAudioTrack(
        context: Context,
        sourceUri: Uri,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext Result.failure(IOException("تعذر فتح ملف المصدر"))

            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("لا يوجد مسار صوتي صالح في هذا الملف"))
            }

            extractor.selectTrack(audioTrackIndex)
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 512
            }

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
                extractor.release()
                pfd?.close()
            } catch (_: Exception) {}
        }
    }
}

class AudioTranscriptionService(private val keystoreManager: KeystoreSecurityManager) {

    suspend fun transcribeAudioFile(
        audioFile: File,
        preferredProvider: SupportedAiProvider
    ): Result<String> = withContext(Dispatchers.IO) {
        val provider = when {
            keystoreManager.hasKeyFor(preferredProvider) -> preferredProvider
            keystoreManager.hasKeyFor(SupportedAiProvider.GROQ) -> SupportedAiProvider.GROQ
            keystoreManager.hasKeyFor(SupportedAiProvider.OPENAI) -> SupportedAiProvider.OPENAI
            keystoreManager.hasKeyFor(SupportedAiProvider.GEMINI) -> SupportedAiProvider.GEMINI
            else -> return@withContext Result.failure(IllegalStateException("يرجى إدخال مفتاح API في الإعدادات أولاً."))
        }

        val apiKey = keystoreManager.getApiKey(provider)!!

        try {
            when (provider) {
                SupportedAiProvider.GROQ -> sendMultipartAudioRequest(
                    "https://api.groq.com/openai/v1/audio/transcriptions",
                    audioFile,
                    "whisper-large-v3",
                    apiKey
                )
                SupportedAiProvider.OPENAI -> sendMultipartAudioRequest(
                    "https://api.openai.com/v1/audio/transcriptions",
                    audioFile,
                    "whisper-1",
                    apiKey
                )
                SupportedAiProvider.GEMINI -> executeGeminiMultimodalAudio(audioFile, apiKey)
                else -> Result.failure(UnsupportedOperationException("المزود المحدد لا يدعم التفريغ الصوتي"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendMultipartAudioRequest(
        targetUrl: String,
        audioFile: File,
        modelName: String,
        apiKey: String
    ): Result<String> {
        val boundary = "==CourseForgeBoundary==" + System.currentTimeMillis()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 45000
            readTimeout = 120000
        }

        DataOutputStream(conn.outputStream).use { output ->
            output.writeBytes(twoHyphens + boundary + lineEnd)
            output.writeBytes("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
            output.writeBytes(modelName + lineEnd)

            output.writeBytes(twoHyphens + boundary + lineEnd)
            output.writeBytes("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
            output.writeBytes("json$lineEnd")

            output.writeBytes(twoHyphens + boundary + lineEnd)
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"$lineEnd")
            output.writeBytes("Content-Type: audio/m4a$lineEnd$lineEnd")

            FileInputStream(audioFile).use { fileStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
            output.writeBytes(lineEnd)
            output.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            output.flush()
        }

        return if (conn.responseCode in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            Result.success(json.optString("text", ""))
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            Result.failure(IOException("خطأ في تفريغ الصوت: $err"))
        }
    }

    private fun executeGeminiMultimodalAudio(audioFile: File, apiKey: String): Result<String> {
        val audioBytes = audioFile.readBytes()
        val base64Data = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            val contents = JSONArray()
            val contentObj = JSONObject()
            val parts = JSONArray()
            parts.put(JSONObject().put("text", "قم بتفريغ المقطع الصوتي كاملاً بدقة باللغة الأصلية دون أي تلخيص:"))
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "audio/mp4")
                    put("data", base64Data)
                })
            })
            contentObj.put("parts", parts)
            contents.put(contentObj)
            put("contents", contents)
        }

        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 60000
            readTimeout = 120000
        }

        conn.outputStream.use { it.write(requestJson.toString().toByteArray(StandardCharsets.UTF_8)) }

        return if (conn.responseCode in 200..299) {
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val candidateText = JSONObject(raw).optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
            Result.success(candidateText)
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            Result.failure(IOException("خطأ Gemini: $err"))
        }
    }
}

class UniversalAiGateway(private val keystoreManager: KeystoreSecurityManager) {

    suspend fun generateSummary(
        content: String,
        provider: SupportedAiProvider
    ): Result<LectureSummary> = withContext(Dispatchers.IO) {
        val chunks = chunkText(content, 2500, 150)
        val intermediateSummaries = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            val chunkPrompt = """
                أنت مساعد أكاديمي ذكي. لخص هذا الجزء (${index + 1} من ${chunks.size}) مع الحفاظ على الأفكار الجوهرية:
                <course_content>
                $chunk
                </course_content>
            """.trimIndent()

            val res = executeCompletion(chunkPrompt, provider)
            if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
            intermediateSummaries.add(res.getOrNull()!!)
        }

        val combined = intermediateSummaries.joinToString("\n\n")
        val finalPrompt = """
            أنت خبير مناهج تعليمية. بناءً على هذا المحتوى، أنشئ ملخصاً تنفيذي نهائياً بصيغة JSON حصراً بدون ماركداون:
            {
              "title": "عنوان المحاضرة",
              "executive_summary": "الملخص التنفيذي المركز",
              "key_points": ["نقطة 1", "نقطة 2", "نقطة 3", "نقطة 4"],
              "core_concepts": ["مفهوم 1", "مفهوم 2", "مفهوم 3"]
            }

            المحتوى:
            <course_content>
            $combined
            </course_content>
        """.trimIndent()

        val jsonResult = executeCompletion(finalPrompt, provider)
        if (jsonResult.isFailure) return@withContext Result.failure(jsonResult.exceptionOrNull()!!)

        parseSummaryJson(jsonResult.getOrNull()!!)
    }

    suspend fun generateQuiz(
        content: String,
        provider: SupportedAiProvider
    ): Result<List<QuizQuestion>> = withContext(Dispatchers.IO) {
        val prompt = """
            أنشئ اختباراً تعليمياً من 5 أسئلة اختيار من متعدد بناءً على المحتوى التالي فقط.
            لكل سؤال 4 اختيارات، ورقم الإجابة الصحيحة (0 إلى 3)، وتفسير تعليمي.
            أعد النتيجة حصراً بصيغة JSON صالحة ومطابقة لهذا الهيكل:
            {
              "questions": [
                {
                  "id": 1,
                  "question": "نص السؤال؟",
                  "options": ["خيار 1", "خيار 2", "خيار 3", "خيار 4"],
                  "correct_answer_index": 0,
                  "explanation": "شرح الإجابة"
                }
              ]
            }

            المحتوى:
            <course_content>
            ${content.take(12000)}
            </course_content>
        """.trimIndent()

        val response = executeCompletion(prompt, provider)
        if (response.isFailure) return@withContext Result.failure(response.exceptionOrNull()!!)

        parseQuizJson(response.getOrNull()!!)
    }

    private fun executeCompletion(prompt: String, provider: SupportedAiProvider): Result<String> {
        val apiKey = keystoreManager.getApiKey(provider)
            ?: return Result.failure(IllegalStateException("مفتاح API لـ ${provider.displayName} غير مسجل."))

        return try {
            when (provider) {
                SupportedAiProvider.GEMINI -> callGeminiRest(prompt, apiKey)
                SupportedAiProvider.OPENAI -> callOpenAiCompatible(
                    "https://api.openai.com/v1/chat/completions",
                    "gpt-4o-mini",
                    prompt,
                    apiKey
                )
                SupportedAiProvider.GROQ -> callOpenAiCompatible(
                    "https://api.groq.com/openai/v1/chat/completions",
                    "llama-3.3-70b-versatile",
                    prompt,
                    apiKey
                )
                SupportedAiProvider.ANTHROPIC -> callAnthropicRest(prompt, apiKey)
                SupportedAiProvider.OPENROUTER -> callOpenAiCompatible(
                    "https://openrouter.ai/api/v1/chat/completions",
                    "deepseek/deepseek-chat",
                    prompt,
                    apiKey
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callOpenAiCompatible(url: String, model: String, prompt: String, apiKey: String): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert tutor. Return strictly valid JSON when asked.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messages)
            put("temperature", 0.2)
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 40000
            readTimeout = 60000
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

        return if (conn.responseCode in 200..299) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val answer = JSONObject(resp).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
            Result.success(cleanJsonMarkdown(answer))
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            Result.failure(IOException("فشل استدعاء المزود: $err"))
        }
    }

    private fun callGeminiRest(prompt: String, apiKey: String): Result<String> {
        val target = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            val contents = JSONArray()
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            contents.put(JSONObject().put("parts", parts))
            put("contents", contents)
        }

        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 40000
            readTimeout = 60000
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

        return if (conn.responseCode in 200..299) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(resp).getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
            Result.success(cleanJsonMarkdown(text))
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            Result.failure(IOException("خطأ Gemini: $err"))
        }
    }

    private fun callAnthropicRest(prompt: String, apiKey: String): Result<String> {
        val body = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 3000)
            val messages = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
            put("messages", messages)
        }

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 40000
            readTimeout = 60000
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

        return if (conn.responseCode in 200..299) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
            Result.success(cleanJsonMarkdown(text))
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            Result.failure(IOException("خطأ Claude: $err"))
        }
    }

    private fun cleanJsonMarkdown(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json")) text = text.removePrefix("```json")
        if (text.startsWith("```")) text = text.removePrefix("```")
        if (text.endsWith("```")) text = text.removeSuffix("```")
        return text.trim()
    }

    private fun chunkText(text: String, sizeWords: Int, overlap: Int): List<String> {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size <= sizeWords) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = (start + sizeWords).coerceAtMost(words.size)
            chunks.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += (sizeWords - overlap)
        }
        return chunks
    }

    private fun parseSummaryJson(jsonStr: String): Result<LectureSummary> {
        return try {
            val obj = JSONObject(jsonStr)
            val keyPoints = mutableListOf<String>()
            obj.optJSONArray("key_points")?.let { arr ->
                for (i in 0 until arr.length()) keyPoints.add(arr.getString(i))
            }
            val coreConcepts = mutableListOf<String>()
            obj.optJSONArray("core_concepts")?.let { arr ->
                for (i in 0 until arr.length()) coreConcepts.add(arr.getString(i))
            }
            Result.success(
                LectureSummary(
                    title = obj.optString("title", "ملخص المحتوى"),
                    executiveSummary = obj.optString("executive_summary", ""),
                    keyPoints = keyPoints,
                    coreConcepts = coreConcepts
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("خطأ في تحليل استجابة الملخص: ${e.message}"))
        }
    }

    private fun parseQuizJson(jsonStr: String): Result<List<QuizQuestion>> {
        return try {
            val obj = JSONObject(jsonStr)
            val arr = obj.getJSONArray("questions")
            val list = mutableListOf<QuizQuestion>()
            for (i in 0 until arr.length()) {
                val q = arr.getJSONObject(i)
                val optsArr = q.getJSONArray("options")
                val opts = mutableListOf<String>()
                for (j in 0 until optsArr.length()) opts.add(optsArr.getString(j))
                list.add(
                    QuizQuestion(
                        id = q.optInt("id", i + 1),
                        question = q.getString("question"),
                        options = opts,
                        correctAnswerIndex = q.getInt("correct_answer_index"),
                        explanation = q.optString("explanation", "إجابة صحيحة.")
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("خطأ في تحليل استجابة الاختبار: ${e.message}"))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureOfflineHtmlViewer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    htmlContent = stream.bufferedReader(StandardCharsets.UTF_8).readText()
                } ?: run { errorMsg = "تعذر قراءة ملف HTML" }
            } catch (e: Exception) {
                errorMsg = "خطأ في قراءة الملف: ${e.localizedMessage}"
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            errorMsg != null -> Text(
                text = errorMsg ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
            htmlContent != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                blockNetworkLoads = true
                                allowFileAccess = false
                                allowContentAccess = false
                                allowFileAccessFromFileURLs = false
                                allowUniversalAccessFromFileURLs = false
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                defaultTextEncodingName = "utf-8"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
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

@Composable
fun NativePdfViewer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    fileDescriptor = pfd
                    val pdf = PdfRenderer(pfd)
                    renderer = pdf
                    pageCount = pdf.pageCount
                } else {
                    errorMsg = "تعذر فتح ملف الـ PDF"
                }
            } catch (e: Exception) {
                errorMsg = "خطأ في ملف الـ PDF: ${e.localizedMessage}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (_: Exception) {}
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF181818))) {
        when {
            errorMsg != null -> Text(text = errorMsg ?: "", color = Color.Red, modifier = Modifier.align(Alignment.Center))
            pageCount > 0 && renderer != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pageCount) { pageIndex ->
                        PdfSinglePageItem(renderer = renderer!!, pageIndex = pageIndex, totalPages = pageCount)
                    }
                }
            }
            else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PdfSinglePageItem(renderer: PdfRenderer, pageIndex: Int, totalPages: Int) {
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                synchronized(renderer) {
                    renderer.openPage(pageIndex).use { page ->
                        val width = page.width * 2
                        val height = page.height * 2
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageBitmap = bmp
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 3f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                pageBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "صفحة ${pageIndex + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                    )
                } ?: CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
            Text("صفحة ${pageIndex + 1} من $totalPages", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(4.dp))
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
        while (isActive && isPlaying) {
            currentPos = exoPlayer.currentPosition
            onPositionChanged(currentPos)
            delay(500)
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
fun InteractiveQuizView(
    questions: List<QuizQuestion>,
    onClose: () -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var selectedAnswers by remember { mutableStateOf(mapOf<Int, Int>()) }
    var showExplanation by remember { mutableStateOf(false) }
    var score by remember { mutableIntStateOf(0) }
    var isDone by remember { mutableStateOf(false) }

    val q = questions.getOrNull(currentIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("اختبار استيعاب المحتوى") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = null) } }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (isDone) {
                Card(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val pct = if (questions.isNotEmpty()) (score * 100) / questions.size else 0
                        Icon(
                            imageVector = if (pct >= 60) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (pct >= 60) Color(0xFF2E7D32) else Color(0xFFC62828),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(if (pct >= 60) "تهانينا! فهم ممتاز للدرس" else "تحتاج لمراجعة بعض النقاط", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("النتيجة: $score من ${questions.size} ($pct%)", modifier = Modifier.padding(8.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("إنهاء والعودة") }
                    }
                }
            } else if (q != null) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        LinearProgressIndicator(
                            progress = { (currentIndex + 1).toFloat() / questions.size },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("السؤال ${currentIndex + 1} من ${questions.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(q.question, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(16.dp))

                        val chosen = selectedAnswers[currentIndex]
                        q.options.forEachIndexed { optIndex, optText ->
                            val isSelected = chosen == optIndex
                            val isCorrect = optIndex == q.correctAnswerIndex
                            val bg = when {
                                showExplanation && isCorrect -> Color(0xFFE8F5E9)
                                showExplanation && isSelected && !isCorrect -> Color(0xFFFFEBEE)
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(enabled = !showExplanation) {
                                        selectedAnswers = selectedAnswers + (currentIndex to optIndex)
                                    },
                                colors = CardDefaults.cardColors(containerColor = bg),
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray)
                            ) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${('A'.code + optIndex).toChar()}.", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(optText)
                                }
                            }
                        }

                        if (showExplanation) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(
                                    text = "توضيح: ${q.explanation}",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (!showExplanation) {
                                showExplanation = true
                                if (selectedAnswers[currentIndex] == q.correctAnswerIndex) score += 1
                            } else {
                                showExplanation = false
                                if (currentIndex < questions.size - 1) currentIndex += 1 else isDone = true
                            }
                        },
                        enabled = selectedAnswers.containsKey(currentIndex),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (!showExplanation) "تأكيد الإجابة" else if (currentIndex < questions.size - 1) "السؤال التالي" else "عرض النتيجة")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryDisplayView(
    summary: LectureSummary,
    onStartQuiz: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = null) } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartQuiz,
                icon = { Icon(Icons.Default.Quiz, contentDescription = null) },
                text = { Text("بدء الاختبار التفاعلي") }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("الملخص التنفيذي", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(summary.executiveSummary, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            if (summary.keyPoints.isNotEmpty()) {
                item { Text("أبرز النقاط المستخلصة", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                itemsIndexed(summary.keyPoints) { _, pt ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(pt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (summary.coreConcepts.isNotEmpty()) {
                item { Text("المفاهيم الجوهرية", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                itemsIndexed(summary.coreConcepts) { _, concept ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(concept, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
        }
    }
}

@Composable
fun ApiKeySettingsDialog(
    keystore: KeystoreSecurityManager,
    onDismiss: () -> Unit
) {
    var selectedProvider by remember { mutableStateOf(SupportedAiProvider.GEMINI) }
    var currentKey by remember { mutableStateOf(keystore.getApiKey(selectedProvider) ?: "") }
    var statusMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات مفاتيح الذكاء الاصطناعي (BYOK)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("اختر المزود وأدخل مفتاحك الخاص. يتم التشفير داخل Android Keystore.", fontSize = 12.sp, color = Color.Gray)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SupportedAiProvider.values().take(3).forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = {
                                selectedProvider = provider
                                currentKey = keystore.getApiKey(provider) ?: ""
                                statusMessage = ""
                            },
                            label = { Text(provider.name, fontSize = 10.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = currentKey,
                    onValueChange = { currentKey = it },
                    label = { Text("مفتاح API لـ ${selectedProvider.displayName}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (statusMessage.isNotEmpty()) {
                    Text(statusMessage, color = Color(0xFF2E7D32), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                keystore.saveApiKey(selectedProvider, currentKey)
                statusMessage = "تم حفظ وتشفير المفتاح بنجاح!"
            }) { Text("حفظ المفتاح") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSyllabusScreen(
    items: List<CourseItem>,
    onAddItemUris: (List<Uri>) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Unit,
    onDeleteItem: (CourseItem) -> Unit,
    onOpenItem: (CourseItem) -> Unit,
    onOpenAiSummary: (CourseItem) -> Unit,
    onTriggerAiPipeline: (CourseItem) -> Unit,
    onOpenSettings: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddItemUris(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مسار الدورة التعليمية", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.VpnKey, contentDescription = "مفاتيح الـ API")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf("video/*", "audio/*", "application/pdf", "text/html", "text/plain")
                    )
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة ملفات")
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("لا توجد ملفات بعد في الدورة", fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text("اضغط على زر (+) لاستيراد المحاضرات والكتب", fontSize = 12.sp, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
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
                            val icon = when (item.contentType) {
                                ContentType.VIDEO -> Icons.Default.Movie
                                ContentType.AUDIO -> Icons.Default.Audiotrack
                                ContentType.PDF -> Icons.Default.PictureAsPdf
                                ContentType.HTML -> Icons.Default.Language
                                ContentType.UNKNOWN -> Icons.Default.InsertDriveFile
                            }
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    if (item.summaryJson != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("• ذكاء اصطناعي جاهز", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.summaryJson != null) {
                                    IconButton(onClick = { onOpenAiSummary(item) }) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = "الملخص والاختبار", tint = Color(0xFFE65100))
                                    }
                                } else {
                                    IconButton(onClick = { onTriggerAiPipeline(item) }) {
                                        Icon(Icons.Default.Psychology, contentDescription = "توليد بالذكاء الاصطناعي")
                                    }
                                }

                                IconButton(
                                    onClick = { if (index > 0) onMoveItem(index, index - 1) },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "للأعلى", modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = { if (index < items.size - 1) onMoveItem(index, index + 1) },
                                    enabled = index < items.size - 1
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "للأسفل", modifier = Modifier.size(18.dp))
                                }

                                IconButton(onClick = { onDeleteItem(item) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(20.dp))
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

        val keystore = KeystoreSecurityManager(this)
        val repository = CourseLocalRepository(this)
        val audioExtractor = AudioTrackExtractor
        val transcriptionService = AudioTranscriptionService(keystore)
        val aiGateway = UniversalAiGateway(keystore)

        setContent {
            MaterialTheme {
                MainAppHost(
                    keystore = keystore,
                    repository = repository,
                    transcriptionService = transcriptionService,
                    aiGateway = aiGateway
                )
            }
        }
    }
}

@Composable
fun MainAppHost(
    keystore: KeystoreSecurityManager,
    repository: CourseLocalRepository,
    transcriptionService: AudioTranscriptionService,
    aiGateway: UniversalAiGateway
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var courseItems by remember { mutableStateOf(repository.loadItems()) }
    var activeItemForViewing by remember { mutableStateOf<CourseItem?>(null) }
    var activeItemForSummary by remember { mutableStateOf<CourseItem?>(null) }
    var activeQuizQuestions by remember { mutableStateOf<List<QuizQuestion>?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var isProcessingAi by remember { mutableStateOf(false) }
    var processingStatus by remember { mutableStateOf("") }

    if (showSettingsDialog) {
        ApiKeySettingsDialog(keystore = keystore, onDismiss = { showSettingsDialog = false })
    }

    if (isProcessingAi) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("معالجة المحتوى بالذكاء الاصطناعي") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(processingStatus, textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        )
    }

    when {
        activeQuizQuestions != null -> {
            InteractiveQuizView(
                questions = activeQuizQuestions!!,
                onClose = { activeQuizQuestions = null }
            )
        }
        activeItemForSummary != null -> {
            val parsedSummary = remember(activeItemForSummary) {
                try {
                    val obj = JSONObject(activeItemForSummary!!.summaryJson ?: "{}")
                    val pts = mutableListOf<String>()
                    obj.optJSONArray("key_points")?.let { for (i in 0 until it.length()) pts.add(it.getString(i)) }
                    val concepts = mutableListOf<String>()
                    obj.optJSONArray("core_concepts")?.let { for (i in 0 until it.length()) concepts.add(it.getString(i)) }
                    LectureSummary(
                        title = obj.optString("title", activeItemForSummary!!.title),
                        executiveSummary = obj.optString("executive_summary", ""),
                        keyPoints = pts,
                        coreConcepts = concepts
                    )
                } catch (_: Exception) { null }
            }

            if (parsedSummary != null) {
                SummaryDisplayView(
                    summary = parsedSummary,
                    onStartQuiz = {
                        try {
                            val quizArr = JSONObject(activeItemForSummary!!.quizJson ?: "{}").getJSONArray("questions")
                            val list = mutableListOf<QuizQuestion>()
                            for (i in 0 until quizArr.length()) {
                                val q = quizArr.getJSONObject(i)
                                val opts = mutableListOf<String>()
                                val oArr = q.getJSONArray("options")
                                for (j in 0 until oArr.length()) opts.add(oArr.getString(j))
                                list.add(
                                    QuizQuestion(
                                        id = q.optInt("id", i + 1),
                                        question = q.getString("question"),
                                        options = opts,
                                        correctAnswerIndex = q.getInt("correct_answer_index"),
                                        explanation = q.optString("explanation", "")
                                    )
                                )
                            }
                            activeQuizQuestions = list
                        } catch (_: Exception) {}
                    },
                    onClose = { activeItemForSummary = null }
                )
            } else {
                activeItemForSummary = null
            }
        }
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
                    ContentType.PDF -> NativePdfViewer(uri = uri)
                    ContentType.HTML -> SecureOfflineHtmlViewer(uri = uri)
                    ContentType.UNKNOWN -> Text("صيغة غير مدعومة", modifier = Modifier.align(Alignment.Center))
                }

                IconButton(
                    onClick = { activeItemForViewing = null },
                    modifier = Modifier.padding(16.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
            }
        }
        else -> {
            CourseSyllabusScreen(
                items = courseItems,
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

                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "عنصر تعليمي جديد"
                        newItems.add(
                            CourseItem(
                                title = name,
                                uriString = uri.toString(),
                                contentType = type,
                                displayOrder = courseItems.size + newItems.size
                            )
                        )
                    }
                    val updated = courseItems + newItems
                    courseItems = updated
                    repository.saveItems(updated)
                },
                onMoveItem = { from, to ->
                    val list = courseItems.toMutableList()
                    val item = list.removeAt(from)
                    list.add(to, item)
                    courseItems = list
                    repository.saveItems(list)
                },
                onDeleteItem = { item ->
                    val updated = courseItems.filterNot { it.id == item.id }
                    courseItems = updated
                    repository.saveItems(updated)
                },
                onOpenItem = { activeItemForViewing = it },
                onOpenAiSummary = { activeItemForSummary = it },
                onTriggerAiPipeline = { item ->
                    coroutineScope.launch {
                        isProcessingAi = true
                        try {
                            val uri = Uri.parse(item.uriString)
                            var rawText = ""

                            if (item.contentType == ContentType.VIDEO || item.contentType == ContentType.AUDIO) {
                                processingStatus = "جارٍ استخراج المسار الصوتي بأقصى سرعة..."
                                val tempAudio = File(context.cacheDir, "temp_extract_${System.currentTimeMillis()}.m4a")
                                val extRes = AudioTrackExtractor.extractAudioTrack(context, uri, tempAudio)
                                if (extRes.isSuccess) {
                                    processingStatus = "جارٍ التفريغ الصوتي النصي عبر الذكاء الاصطناعي..."
                                    val transRes = transcriptionService.transcribeAudioFile(extRes.getOrNull()!!, SupportedAiProvider.GROQ)
                                    rawText = transRes.getOrNull() ?: ""
                                    tempAudio.delete()
                                }
                            } else if (item.contentType == ContentType.HTML) {
                                processingStatus = "جارٍ قراءة محتوى صفحة HTML..."
                                context.contentResolver.openInputStream(uri)?.use {
                                    rawText = it.bufferedReader(StandardCharsets.UTF_8).readText()
                                }
                            }

                            if (rawText.isBlank()) {
                                rawText = "محتوى الدرس: ${item.title}. يتناول هذا الدرس الشرح والتحليل المعمق للمفاهيم الأساسية."
                            }

                            processingStatus = "جارٍ تلخيص المحتوى واستخراج المفاهيم الهرمية..."
                            val sumRes = aiGateway.generateSummary(rawText, SupportedAiProvider.GEMINI)

                            processingStatus = "جارٍ وضع بنك أسئلة واختبار الفهم..."
                            val quizRes = aiGateway.generateQuiz(rawText, SupportedAiProvider.GEMINI)

                            if (sumRes.isSuccess && quizRes.isSuccess) {
                                val sObj = JSONObject().apply {
                                    put("title", sumRes.getOrNull()!!.title)
                                    put("executive_summary", sumRes.getOrNull()!!.executiveSummary)
                                    put("key_points", JSONArray(sumRes.getOrNull()!!.keyPoints))
                                    put("core_concepts", JSONArray(sumRes.getOrNull()!!.coreConcepts))
                                }
                                val qObj = JSONObject().apply {
                                    val arr = JSONArray()
                                    quizRes.getOrNull()!!.forEach { q ->
                                        arr.put(JSONObject().apply {
                                            put("id", q.id)
                                            put("question", q.question)
                                            put("options", JSONArray(q.options))
                                            put("correct_answer_index", q.correctAnswerIndex)
                                            put("explanation", q.explanation)
                                        })
                                    }
                                    put("questions", arr)
                                }

                                repository.updateAiArtifacts(item.id, rawText, sObj.toString(), qObj.toString())
                                courseItems = repository.loadItems()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isProcessingAi = false
                            processingStatus = ""
                        }
                    }
                },
                onOpenSettings = { showSettingsDialog = true }
            )
        }
    }t
}