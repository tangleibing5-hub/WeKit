package dev.ujhhgtg.wekit.features.items.shortvideos

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

@Feature(
    name = "移除评论区广告",
    categories = ["视频号"],
    description = "隐藏视频号评论区中的广告卡片"
)
object RemoveCommentAds : SwitchFeature(), IResolveDex {

    private val hiddenViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    override fun onEnable() {
        methodBindAdComment.hookBefore { param ->
            val itemView = findItemView(param.args.getOrNull(0))
            if (itemView == null) {
                WeLogger.i(TAG, "ad convert: cannot get itemView from ${param.args.getOrNull(0)?.javaClass?.name}")
                return@hookBefore
            }
            hideView(itemView)
            WeLogger.i(TAG, "hidden finder comment ad")
        }
        methodBindOldComment.hookBefore { param ->
            val itemView = findItemView(param.args.getOrNull(0)) ?: return@hookBefore
            if (itemView is ViewGroup && containsAdText(itemView)) {
                hideView(itemView)
                WeLogger.i(TAG, "hidden old-convert comment ad")
            }
            if (hiddenViews.remove(itemView)) {
                itemView.visibility = View.VISIBLE
                if (itemView.layoutParams != null && itemView.layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    itemView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    itemView.requestLayout()
                }
            }
        }
    }

    private fun findItemView(holder: Any?): View? {
        if (holder == null) return null
        return runCatching {
            holder.reflekt().firstField { name = "itemView"; superclass() }.get() as? View
        }.getOrNull()
    }

    private fun containsAdText(root: ViewGroup): Boolean {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is TextView && view.text?.contains(AD_KEYWORD) == true) return true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    stack.add(view.getChildAt(i))
                }
            }
        }
        return false
    }

    private fun hideView(view: View) {
        hiddenViews.add(view)
        view.visibility = View.GONE
        if (view.layoutParams == null || view.layoutParams.height == 0) return
        view.layoutParams.height = 0
        view.requestLayout()
    }

    private val methodBindAdComment by dexMethod {
        matcher {
            usingEqStrings("com/tencent/mm/plugin/finder/convert/comment/FinderAdCommentConvert")
            returnType = "void"
            paramCount = 6
        }
    }

    private val methodBindOldComment by dexMethod {
        matcher {
            usingEqStrings("com/tencent/mm/plugin/finder/convert/FinderFeedCommentConvert")
            returnType = "void"
        }
    }

    private const val AD_KEYWORD = "广告"
}
