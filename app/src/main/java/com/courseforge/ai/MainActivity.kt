
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

const val APP_VERSION = "3.0.0"

enum class FileType { PDF, VIDEO, AUDIO, HTML, UNKNOWN }
enum class AiProvider { GROQ, GEMINI, OPENAI, ANTHROPIC, OPENROUTER }

data class Course(val id: String, val title: String, val createdAt: Long = System.currentTimeMillis())
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
        
        // فحص الترقية بين الإصدارات ونقل البيانات القديمة بدون فقدان
        performVersionMigration(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CourseForgeMainNavigation()
                }
            }
        }
    }
}

/**
 * نظام الترحيل بين الإصدارات (Schema & State Migration) لضمان عدم تلف البيانات عند التحديث
 */
fun performVersionMigration(context: Context) {
    val metaPrefs = context.getSharedPreferences("cf_meta", Context.MODE_PRIVATE)
    val lastVersion = metaPrefs.getString("installed_version", "1.0.0")

    if (lastVersion != APP_VERSION) {
        val legacyDb = context.getSharedPreferences("cf_db", Context.MODE_PRIVATE)
        val v3Db = context.getSharedPreferences("cf_db_v3", Context.MODE_PRIVATE)

        if (!v3Db.contains("courses") && legacyDb.contains("courses")) {
            val oldCourses = legacyDb.getString("courses", "[]")
            val oldItems = legacyDb.getString("course_items", "[]")
            v3Db.edit()
                .putString("courses", oldCourses)
                .putString("course_items", oldItems)