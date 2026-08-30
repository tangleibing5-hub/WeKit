package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.forEach
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "隐藏全局列表分割线",
    categories = ["界面美化"],
    description = "隐藏全局列表（消息、联系人等）里的分割线与白色背景条"
)
object HideGlobalListDividers : SwitchFeature(), IResolveDex {

    private val hideDividers by WePrefs.prefOption("hide_global_dividers", true)
    private val hideWhiteBar by WePrefs.prefOption("hide_global_white_bar", true)

    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            usingEqStrings("MicroMsg.ConversationWithCacheAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
        }
    }

    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    private val methodEnterpriseCardRun by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.contact")
        matcher {
            name = "run"
            usingEqStrings("MicroMsg.EnterpriseBizView", "biz list size = %s")
        }
    }

    private val methodAccountInfoBind by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.preference.AccountInfoPreference"
            name = "w"
        }
    }

    private val recyclerViewClass: Class<*>? by lazy {
        runCatching {
            ClassLoaders.HOST.loadClass("androidx.recyclerview.widget.RecyclerView")
        }.getOrNull()
    }

    private val loggedMessages = ConcurrentHashMap.newKeySet<String>()

    private fun logOnce(message: String) {
        if (loggedMessages.add(message)) {
            WeLogger.d("HideGlobalListDividers", message)
        }
    }

    private fun describeDrawable(drawable: Drawable): String =
        if (drawable is ColorDrawable) String.format("#%08X", drawable.color)
        else drawable.javaClass.simpleName

    private fun isChattingRelated(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("Chatting") || name.contains("chatting")) return true
        val tag = view.tag
        return tag != null && tag.javaClass.name.startsWith("com.tencent.mm.ui.chatting")
    }

    private fun isExempted(view: View): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (isChattingRelated(cur)) return true
            cur = cur.parent as? View
        }

        cur = view.parent as? View
        while (cur != null) {
            if (cur is AbsListView || recyclerViewClass?.isInstance(cur) == true) {
                cur = cur.parent as? View
                while (cur != null) {
                    if (isChattingRelated(cur)) return true
                    cur = cur.parent as? View
                }
                return false
            }
            cur = cur.parent as? View
        }
        return false
    }

    private fun isListContainer(viewGroup: ViewGroup): Boolean {
        if (viewGroup.javaClass.name.contains("Chatting")) return false
        return viewGroup is AbsListView || recyclerViewClass?.isInstance(viewGroup) == true
    }

    private fun clearAllBackgrounds(view: View) {
        if (view !is TextView && view !is ImageView && view.background != null) {
            view.background = null
        }
        (view as? ViewGroup)?.forEach { child ->
            clearAllBackgrounds(child)
        }
    }

    private fun isLightColorDrawable(drawable: Drawable): Boolean {
        if (drawable !is ColorDrawable) return false
        val color = drawable.color
        return (color shr 16 and 0xFF) >= 220 && (color shr 8 and 0xFF) >= 220 && (color and 0xFF) >= 220
    }

    private fun clearInnerBackgrounds(view: View, tag: String) {
        val viewGroup = view as? ViewGroup ?: return
        if (isExempted(viewGroup)) return
        viewGroup.forEach { child ->
            if (child is ViewGroup) {
                val background = child.background
                if (background != null &&
                    (isLightColorDrawable(background) || background is NinePatchDrawable || background is StateListDrawable)
                ) {
                    logOnce(
                        "innerbg[$tag] ${child.javaClass.name} bg=${background.javaClass.simpleName}" +
                            " color=${describeDrawable(background)}"
                    )
                    child.background = null
                }
            }
            clearInnerBackgrounds(child, tag)
        }
    }

    private fun hideDividersIn(view: View) {
        val viewGroup = view as? ViewGroup ?: return
        val maxDividerHeight = (viewGroup.resources.displayMetrics.density * 3.0f).toInt()
        viewGroup.forEach { child ->
            val lpHeight = child.layoutParams?.height ?: -1
            if (
                child.background == null || child is TextView || child is ImageView ||
                lpHeight < 1 || lpHeight > maxDividerHeight
            ) {
                hideDividersIn(child)
            } else {
                child.visibility = View.GONE
            }
        }
    }

    private fun processItem(view: View, tag: String) {
        if (hideWhiteBar && !isExempted(view)) {
            view.background?.let { background ->
                logOnce(
                    "whitebar[$tag] item=${view.javaClass.name} bg=${background.javaClass.simpleName}" +
                        " color=${describeDrawable(background)}"
                )
                view.background = null
            }
            clearInnerBackgrounds(view, tag)
        }
        if (hideDividers) {
            hideDividersIn(view)
        }
    }

    private fun processChildren(viewGroup: ViewGroup) {
        val tag = viewGroup.javaClass.simpleName
        viewGroup.forEach { child ->
            processItem(child, tag)
        }
    }

    private fun walkAndProcess(view: View) {
        if (!hideDividers && !hideWhiteBar) return
        val viewGroup = view as? ViewGroup ?: return
        if (isListContainer(viewGroup)) {
            processChildren(viewGroup)
            return
        }
        viewGroup.forEach { child ->
            walkAndProcess(child)
        }
    }

    override fun onEnable() {
        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup ?: return@hookAfter
                processItem(viewGroup, "conversationGetView")
            }
        }

        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup ?: return@hookAfter
                processItem(viewGroup, "mvvmConversationGetView")
            }
        }

        AbsListView::class.java
            .getDeclaredMethod("obtainView", Int::class.javaPrimitiveType!!, BooleanArray::class.java)
            .hookAfter(50) {
                val view = result as? View ?: return@hookAfter
                val listView = thisObject as? ViewGroup ?: return@hookAfter
                if (isListContainer(listView)) {
                    processItem(view, listView.javaClass.simpleName + "#obtainView")
                }
            }

        View::class.java
            .getDeclaredMethod("onAttachedToWindow")
            .hookAfter(50) {
                val view = thisObject as? View ?: return@hookAfter
                val parent = view.parent as? ViewGroup ?: return@hookAfter
                if (isListContainer(parent)) {
                    processItem(view, parent.javaClass.simpleName)
                }
            }

        AbsListView::class.reflekt()
            .firstMethodOrNull { name = "onLayout" }
            ?.hookBefore(50) {
                val viewGroup = thisObject as? ViewGroup ?: return@hookBefore
                if (isListContainer(viewGroup)) {
                    processChildren(viewGroup)
                }
            }

        recyclerViewClass
            ?.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            ?.hookAfter(50) {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                if (isListContainer(viewGroup)) {
                    processChildren(viewGroup)
                }
            }

        Activity::class.java
            .getDeclaredMethod("onResume")
            .hookAfter(50) {
                val activity = thisObject as? Activity ?: return@hookAfter
                val decorView = activity.window?.decorView as? ViewGroup ?: return@hookAfter
                decorView.post {
                    walkAndProcess(decorView)
                }
            }

        if (!methodEnterpriseCardRun.isPlaceholder) {
            methodEnterpriseCardRun.hookAfter {
                if (!hideDividers) return@hookAfter
                val thisObj = thisObject ?: return@hookAfter
                val first = thisObj.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }
                    .get(thisObj) ?: return@hookAfter
                val itemView = first.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }
                    .get(first) as? View ?: return@hookAfter
                val container = itemView.javaClass.getDeclaredField("e")
                    .apply { isAccessible = true }
                    .get(itemView) as? ViewGroup ?: return@hookAfter
                itemView.background = null
                container.background = null
                clearAllBackgrounds(container)
                hideDividersIn(container)
            }
        } else {
            WeLogger.i("HideGlobalListDividers", "enterpriseCardRun hook not resolved")
        }

        if (!methodAccountInfoBind.isPlaceholder) {
            methodAccountInfoBind.hookAfter {
                if (!hideWhiteBar) return@hookAfter
                val view = args.firstOrNull() as? View ?: return@hookAfter
                clearAllBackgrounds(view)
                view.post {
                    clearAllBackgrounds(view)
                }
                view.postDelayed({
                    clearAllBackgrounds(view)
                }, 100)
            }
        } else {
            WeLogger.i("HideGlobalListDividers", "accountInfoBind hook not resolved")
        }
    }
}
