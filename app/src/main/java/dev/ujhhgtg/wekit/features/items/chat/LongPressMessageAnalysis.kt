package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Manage_search
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Feature(
    name = "长按分析消息",
    categories = ["聊天"],
    description = "在长按消息时可点击「分析」, 读取当前会话的聊天记录, 调用 AI 生成群聊数据分析报告"
)
object LongPressMessageAnalysis : ClickableFeature(), IResolveDex, WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val MENU_ID = 777026

    private var apiBaseUrl by WePrefs.prefOption("analysis_api_base_url", "https://api.openai.com/v1")
    private var apiPath by WePrefs.prefOption("analysis_api_path", "/chat/completions")
    private var apiKey by WePrefs.prefOption("analysis_api_key", "")
    private var modelName by WePrefs.prefOption("analysis_model", "gpt-4o-mini")
    private var sampleLimit by WePrefs.prefOption("analysis_sample_limit", 500)
    private var minLen by WePrefs.prefOption("analysis_min_len", 2)
    private var wordCount by WePrefs.prefOption("analysis_word_count", 40)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ID,
                "分析",
                EditIcon,
                MaterialSymbols.Outlined.Manage_search,
                { msgInfo -> msgInfo.talker.isNotEmpty() },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                showAnalysisDialog(view, msgInfo.talker)
            }
        )
    }

    private fun showAnalysisDialog(view: android.view.View, talker: String) {
        showComposeDialog(view.context) {
            var state by remember {
                mutableStateOf<AnalysisUiState>(AnalysisUiState.Loading)
            }
            LaunchedEffect(talker) {
                state = if (apiKey.isBlank()) {
                    AnalysisUiState.Error("尚未配置 API Key, 请点击功能卡片进行配置")
                } else {
                    runCatching { analyzeConversation(talker) }
                        .fold(
                            onSuccess = { AnalysisUiState.Success(it) },
                            onFailure = { e ->
                                WeLogger.e(TAG, "failed to analyze conversation", e)
                                AnalysisUiState.Error(e.message ?: "分析失败")
                            }
                        )
                }
            }
            AlertDialogContent(
                title = { Text("群聊数据分析") },
                text = {
                    when (val s = state) {
                        is AnalysisUiState.Loading -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Text("正在读取并分析聊天记录...", modifier = Modifier.padding(top = 12.dp))
                        }
                        is AnalysisUiState.Success -> Text(
                            s.report,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                        )
                        is AnalysisUiState.Error -> Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } }
            )
        }
    }

    private sealed interface AnalysisUiState {
        data object Loading : AnalysisUiState
        data class Success(val report: String) : AnalysisUiState
        data class Error(val message: String) : AnalysisUiState
    }

    private suspend fun analyzeConversation(talker: String): String {
        val sample = withContext(Dispatchers.IO) {
            val rows = WeDatabaseApi.executeQuery(
                WeDatabaseApi.Queries.messages(talker, sampleLimit, 0)
            )
            buildSample(rows.asReversed())
        }
        if (sample.isEmpty()) {
            return "该会话没有找到足够的文本消息可供分析"
        }
        return withContext(Dispatchers.IO) {
            callChatCompletions(sample)
        }
    }

    private fun buildSample(rows: List<Map<String, Any?>>): String {
        val lines = buildString {
            for (row in rows) {
                val type = (row["type"] as? Number)?.toInt() ?: 0
                if (type != 1) continue
                val content = (row["content"] as? String)?.trim().orEmpty()
                if (content.length < minLen) continue
                val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
                val sender = if (isSend == 1) "我" else content.substringBefore(':', "").ifBlank { "群友" }
                val text = if (isSend == 1) content else content.substringAfter(':', content)
                append(sender).append(": ").append(text.trim()).append('\n')
            }
        }
        return lines.trim()
    }

    private fun callChatCompletions(sample: String): String {
        val systemPrompt = "你是专业的微信群聊数据分析师。请根据给出的聊天记录生成分析报告, 包括但不限于: " +
            "1. 主要话题与讨论热点; 2. 活跃成员; 3. 高频词汇; 4. 聊天风格特点。请使用中文, 使用清晰的要点格式。"
        val payload = JSONObject()
            .put("model", modelName)
            .put("temperature", 0.7)
            .put(
                "messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", "以下是聊天记录:\n$sample"))
            )
            .put("max_tokens", wordCount * 4)
        val url = apiBaseUrl.trimEnd('/') + apiPath
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("API 请求失败: HTTP ${response.code} ${bodyText.take(200)}")
            }
            val json = JSONObject(bodyText)
            val choices = json.optJSONArray("choices")
            val content = choices?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            if (content.isBlank()) {
                throw IllegalStateException("API 响应中没有找到内容")
            }
            return content
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var newBaseUrl by remember { mutableStateOf(apiBaseUrl) }
            var newKey by remember { mutableStateOf(apiKey) }
            var newModel by remember { mutableStateOf(modelName) }
            AlertDialogContent(
                title = { Text("长按分析消息 - 配置") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        ConfigField("API Base URL", newBaseUrl) { newBaseUrl = it }
                        ConfigField("API Key", newKey) { newKey = it }
                        ConfigField("模型", newModel) { newModel = it }
                        Text(
                            "长按任意消息 → 分析, 将读取当前会话最近 $sampleLimit 条消息生成报告",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    Button({
                        apiBaseUrl = newBaseUrl
                        apiKey = newKey
                        modelName = newModel
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    @Composable
    private fun ConfigField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }
}
