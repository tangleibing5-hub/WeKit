package dev.ujhhgtg.wekit.features.items.chat

import android.os.SystemClock
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.reflection.void

/**
 * 消息进入动画。
 *
 * 基于 WePeak 1.7.0 行为重建：在聊天列表绑定新消息时为其播放入场动画，
 * 支持弹跳 / 平移滑入 / 重力掉落三种风格。原实现通过 ChattingDataAdapterV3
 * 的 onBindViewHolder 驱动 SpringAnimation；这里改用 ViewPropertyAnimator 做
 * 等价的视觉效果，并保留 500ms 防抖与「仅最新消息」判断。
 */
@Feature(
    name = "消息进入动画",
    categories = ["聊天"],
    description = "为聊天列表新进入的消息添加入场动画，支持弹跳、平移滑入、重力掉落三种风格"
)
object MessageEntranceAnimation : SwitchFeature(), IResolveDex {

    private const val MIN_INTERVAL_MS = 500L

    /** 0=弹跳 1=平移滑入 2=重力掉落 */
    private var entranceStyle by prefOption("msg_entrance_style", 0)
    private var bounceAllOnEnter by prefOption("msg_entrance_bounce_all", false)

    private val methodChattingAdapterOnBindViewHolder by dexMethod {
        matcher {
            declaredClass(WeMessageApi.classChattingDataAdapter.clazz)
            name = "onBindViewHolder"
            paramCount(2)
            returnType(void)
        }
    }

    private val lastAnimateAt = ThreadLocal<Long>()

    override fun onEnable() {
        methodChattingAdapterOnBindViewHolder.hookAfter {
            val style = entranceStyle
            if (style !in 0..2) return@hookAfter
            val adapter = thisObject ?: return@hookAfter
            val position = args.getOrNull(1) as? Int ?: return@hookAfter
            val now = SystemClock.elapsedRealtime()
            if (now - (lastAnimateAt.get() ?: 0L) < MIN_INTERVAL_MS) return@hookAfter
            if (!bounceAllOnEnter && !isNewestItem(adapter, position)) return@hookAfter
            val holder = args.getOrNull(0) ?: return@hookAfter
            val itemView = holder.reflekt().firstField { type = View::class.java }.get() as? View
                ?: return@hookAfter
            playEntrance(itemView, style)
            lastAnimateAt.set(now)
        }
    }

    private fun isNewestItem(adapter: Any, position: Int): Boolean {
        val itemCount = adapter.reflekt().firstMethod {
            name = "getItemCount"
            superclass()
        }.invoke(adapter) as? Int ?: return false
        return position >= itemCount - 1
    }

    private fun playEntrance(view: View, style: Int) {
        when (style) {
            0 -> {
                view.alpha = 0f
                view.scaleX = 0.8f
                view.scaleY = 0.8f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(450)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            1 -> {
                view.translationY = view.height.toFloat()
                view.animate()
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            else -> {
                view.translationY = -view.height.toFloat()
                view.animate()
                    .translationY(0f)
                    .setDuration(550)
                    .setInterpolator(BounceInterpolator())
                    .start()
            }
        }
    }
}
