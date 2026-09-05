package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL

@Feature(name = "侧滑栏随机视频", categories = ["美化"], description = "在 WeKit 原有会话侧滑栏中显示可切换的9:16随机视频")
object MainScreenMediaDrawer : SwitchFeature() {
    override val defaultEnabled: Boolean = true

    private var videoUris by prefOption("main_media_video_uris", emptySet<String>())
    private var apiUrls by prefOption("main_media_api_urls", DEFAULT_API)
    private var muted by prefOption("main_media_muted", true)
    private var apiFirst by prefOption("main_media_api_first", false)

    private const val DEFAULT_API = "https://api.yujn.cn/api/zzxjj.php?type=json"

    override fun onEnable() = Unit

    @Composable
    fun MediaDrawerContent(context: Context) {
        var settingsOpen by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Outlined.Movie, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("随机视频", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { settingsOpen = !settingsOpen }) {
                    Icon(
                        if (settingsOpen) MaterialSymbols.Outlined.Close else MaterialSymbols.Outlined.Settings,
                        contentDescription = if (settingsOpen) "关闭视频设置" else "打开视频设置",
                    )
                }
            }
            if (settingsOpen) VideoSettings(context) else VideoPlayer(context)
        }
    }

    @Composable
    private fun VideoPlayer(context: Context) {
        val scope = rememberCoroutineScope()
        var videoUrl by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        val nextVideo = {
            if (!loading) scope.launch(Dispatchers.IO) {
                loading = true
                val local = videoUris.toList()
                videoUrl = if (apiFirst || local.isEmpty()) fetchApiUrl() else local.randomOrNull()
                loading = false
            }
        }
        LaunchedEffect(Unit) { nextVideo() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (videoUrl != null) {
                AndroidView(
                    factory = { VideoView(it) },
                    update = { view ->
                        if (view.tag != videoUrl) {
                            view.tag = videoUrl
                            view.setVideoURI(Uri.parse(videoUrl))
                            view.setOnPreparedListener { player ->
                                player.isLooping = true
                                player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                                view.start()
                            }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black)
                        .clickable { nextVideo() },
                )
            } else {
                Text(if (loading) "正在加载视频..." else "没有可播放的视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = nextVideo, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                Icon(MaterialSymbols.Outlined.Movie, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("下一个视频")
            }
        }
    }

    @Composable
    private fun VideoSettings(context: Context) {
        var apiText by remember { mutableStateOf(apiUrls) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { pickVideos(context) }, modifier = Modifier.fillMaxWidth()) {
                Icon(MaterialSymbols.Outlined.Movie, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("选择本地视频")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = muted, onCheckedChange = { muted = it })
                Text("视频静音")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = apiFirst, onCheckedChange = { apiFirst = it })
                Text("优先使用线路视频")
            }
            OutlinedTextField(
                value = apiText,
                onValueChange = { apiText = it; apiUrls = it },
                label = { Text("视频线路，每行一个地址") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text("本地视频 ${videoUris.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun pickVideos(context: Context) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach { uri ->
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                videoUris = videoUris + uris.map(Uri::toString)
                finish()
            }
            launcher.launch(arrayOf("video/mp4", "video/*"))
        }
    }

    private fun fetchApiUrl(): String? = apiUrls.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .firstNotNullOfOrNull { endpoint ->
            runCatching {
                val body = URL(endpoint).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                }.getInputStream().bufferedReader().use { it.readText() }
                if (body.trim().startsWith("http")) body.trim().trim('"')
                else JSONObject(body).optString("data").takeIf { it.startsWith("http") }
            }.getOrNull()
        }
}
