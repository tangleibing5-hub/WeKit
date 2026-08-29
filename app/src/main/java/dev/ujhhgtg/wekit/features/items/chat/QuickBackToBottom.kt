package dev.ujhhgtg.wekit.features.items.chat

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.util.Collections
import java.util.WeakHashMap

/**
 * 快捷回底。
 *
 * 基于 WePeak 1.7.0 行为重建：聊天界面远离最新消息时，复用右下角
 * 「x条新消息」气泡，将其文本替换为「回到最新」，点击一键滚回最新消息。
 * 原实现以 HistoryMsgTongueComponent 的 OnPreDrawListener 判定气泡可见性并
 * 替换文本；这里在 ChatFooter 上挂 PreDraw 监听做等价处理。原反编译 matcher：
 *  - methodComponentInit: HistoryMsgTongueComponent / [onChattingInit]
 *  - methodGetMsgCount: MicroMsg.MsgInfoStorage / getMsgCount ... count:%d
 *  - methodLocationByMsgId: ChattingDataAdapterV3 / [locationByMsgId] position:%s mode:%s
 *  - methodSetShowHistoryMsgTipId: com.tencent.mm.ui.chatting.adapter / [setShowHistoryMsgTipId] pos:%s
 */
@Feature(
    name = "快捷回底",
    categories = ["聊天"],
    description = "聊天界面远离最新消息时，将右下角「x条新消息」气泡替换为「回到最新」，点击一键回到最新消息"
)
object QuickBackToBottom : SwitchFeature() {

    private const val TIP_TEXT = "回到最新"
    private const val UPDATE_MIN_INTERVAL_MS = 300L

    private val hookedFooters = Collections.newSetFromMap(WeakHashMap<ChatFooter, Boolean>())
    private val lastTipUpdate = ThreadLocal<Long>()

    override fun onEnable() {
        ChatFooter::class.java.reflekt()
            .firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }
            .hookAfter {
                val chatFooter = thisObject as ChatFooter
                if (!hookedFooters.add(chatFooter)) return@hookAfter
                chatFooter.viewTreeObserver.addOnPreDrawListener(
                    ViewTreeObserver.OnPreDrawListener {
                        updateHistoryTip(chatFooter)
                        true
                    }
                )
            }
    }

    private fun updateHistoryTip(footer: ChatFooter) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastTipUpdate.get() ?: 0L) < UPDATE_MIN_INTERVAL_MS) return
        val tip = findHistoryTip(footer) ?: return
        val textView = tip.children.find { it is TextView } as? TextView ?: return
        val current = textView.text?.toString().orEmpty()
        if (current.isEmpty() || current == TIP_TEXT) return
        textView.text = TIP_TEXT
        tip.setOnClickListener {
            scrollToLatest(tip)
        }
        lastTipUpdate.set(now)
    }

    private fun findHistoryTip(root: View): ViewGroup? {
        if (root !is ViewGroup) return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v !== root && v is ViewGroup && v.visibility == View.VISIBLE && v.childCount <= 3) {
                val hasImage = v.children.any { it is ImageView }
                val hasText = v.children.any { it is TextView }
                if (hasImage && hasText) return v
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
            }
        }
        return null
    }

    private fun scrollToLatest(from: View) {
        var v: View? = from
        while (v != null) {
            val rv = v as? RecyclerView
            if (rv != null) {
                val adapter = rv.adapter ?: return
                rv.scrollToPosition(adapter.itemCount - 1)
                return
            }
            v = v.parent as? View
        }
    }
}
