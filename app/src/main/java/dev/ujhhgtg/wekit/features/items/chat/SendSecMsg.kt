package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.Shield
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi.ActionItem
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.reflect.Field

@Feature(
    name = "发送安全消息",
    categories = ["聊天"],
    description = "给发送的文本消息注入安全标记, 支持自动注入与输入框前缀触发"
)
object SendSecMsg : ClickableFeature(), IResolveDex {

    private const val TAG = "SendSecMsg"

    private const val KEY_MODE = "sec_msg_mode"
    private const val KEY_TRIGGER_PREFIX = "sec_msg_trigger_prefix"

    private const val MODE_AUTO = 0
    private const val MODE_PREFIX = 2

    private const val TEXT_MESSAGE_TYPE = 1
    private const val SEC_MSG_NODE = "<sec_msg_node><sfn>1</sfn><bubble-type>2</bubble-type></sec_msg_node>"

    private var mode by WePrefs.prefOption(KEY_MODE, MODE_AUTO)
    private var triggerPrefix by WePrefs.prefOption(KEY_TRIGGER_PREFIX, "#sec")

    @Volatile
    private var prefixTriggered = false

    override fun onEnable() {
        methodInsertMessage.hookBefore {
            val msg = args.getOrNull(0) ?: return@hookBefore
            if (messageTypeField.getInt(msg) != TEXT_MESSAGE_TYPE) return@hookBefore
            val shouldInject = mode == MODE_AUTO || prefixTriggered
            prefixTriggered = false
            if (shouldInject) {
                methodMergeSecNode.method.invoke(null, msg, SEC_MSG_NODE, false)
            }
        }
        WeChatInputBarMenuApi.methodSendMessage.hookBefore {
            if (mode != MODE_PREFIX) return@hookBefore
            val chatFooter = findChatFooter() ?: return@hookBefore
            val lastText = chatFooter.getLastText()
            prefixTriggered = false
            if (lastText.isEmpty()) return@hookBefore
            if (lastText.startsWith(triggerPrefix)) {
                chatFooter.setLastText(lastText.removePrefix(triggerPrefix))
                prefixTriggered = true
            }
        }
        WeChatInputBarMenuApi.addProvider(menuProvider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(menuProvider)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selected by remember { mutableStateOf(mode) }
            AlertDialogContent(
                title = { Text("发送安全消息") },
                text = {
                    Column {
                        ModeRow("自动", "所有发送的文本消息都带安全标记", MODE_AUTO, selected) { selected = it }
                        ModeRow("输入框前缀", "输入框以 $triggerPrefix 开头时发送安全消息", MODE_PREFIX, selected) { selected = it }
                    }
                },
                confirmButton = {
                    Button({
                        mode = selected
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Composable
    private fun ModeRow(label: String, desc: String, value: Int, selected: Int, onSelect: (Int) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(value) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun findChatFooter(): ChatFooter? {
        return (thisObject?.reflekt()?.firstFieldOrNull { type = ChatFooter::class.java }?.get()) as? ChatFooter
    }

    private val messageTypeField: Field by lazy {
        instanceFields(WeMessageApi.classMsgInfo.clazz).first { it.name == "field_type" }
    }

    private fun instanceFields(clazz: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                field.isAccessible = true
                yield(field)
            }
            current = current.superclass
        }
    }

    private object SecMenuProvider : WeChatInputBarMenuApi.IActionItemsProvider {
        override fun getActionItems(): List<ActionItem> = listOf(
            ActionItem(
                id = "send_sec_msg",
                icon = Shield,
                label = "安全消息",
                onClick = { _, chatFooter ->
                    val lastText = chatFooter.getLastText()
                    when {
                        lastText.startsWith(SendSecMsg.triggerPrefix) -> {
                            SendSecMsg.triggerPrefix = lastText.removePrefix(SendSecMsg.triggerPrefix)
                            SendSecMsg.prefixTriggered = true
                            showToast("已标记为安全消息")
                        }
                        SendSecMsg.mode == MODE_PREFIX -> {
                            chatFooter.setLastText(if (lastText.isEmpty()) SendSecMsg.triggerPrefix else "${SendSecMsg.triggerPrefix} $lastText")
                            showToast("已添加安全消息前缀")
                        }
                        else -> {
                            SendSecMsg.prefixTriggered = true
                            showToast("下一条消息将作为安全消息发送")
                        }
                    }
                }
            )
        )
    }

    private val methodInsertMessage by dexMethod {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("Error insert message msg:%s talker:%s")
            returnType = "long"
            paramCount = 2
        }
    }

    private val methodMergeSecNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>")
            returnType = "void"
            paramCount = 3
        }
    }
}
