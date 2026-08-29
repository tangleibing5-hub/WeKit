package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 启用侧滑栏。
 *
 * 基于 WePeak 1.7.0 行为重建：在微信主界面（LauncherUI/MainTabUI）左侧注入
 * 侧滑栏 overlay，从屏幕左边缘右滑呼出，内容区做 3D 旋转联动。反编译依据：
 *  - SidebarFeature.java：W(activity) 注入 overlay、hook MainTabUI.doOnCreate、
 *    MainTabUI$TabsAdapter.onPageSelected、LauncherUI.startChatting/closeChatting、
 *    Activity.dispatchTouchEvent（matcher 均带 Class.forName 硬编码 fallback）
 *  - ym8.java：scrimView + sidebarPanel 开合动画、内容区 rotationY(-18°) 联动
 * 仅重建 classic 风格；floating 风格依赖 WePeak 独立的 cy3 功能，此处跳过。
 */
@Feature(
    name = "启用侧滑栏",
    categories = ["界面美化"],
    description = "在微信主界面启用左侧侧滑栏，从屏幕左边缘右滑呼出"
)
object SidebarFeature : SwitchFeature() {

    private const val TAG = "SidebarFeature"
    private const val PANEL_WIDTH_DP = 280
    private const val SWIPE_EDGE_DP = 32
    private const val SWIPE_THRESHOLD_DP = 48
    private const val SWIPE_MAX_SLOP_DP = 72

    private var sidebarStyle by prefOption("sidebar_style", "classic")

    private val overlays = ConcurrentHashMap<Activity, SidebarOverlay>()

    @Volatile
    private var currentTab = 0

    override fun onEnable() {
        if (sidebarStyle != "classic") return

        // 主界面创建时注入侧滑栏（与 AddMainScreenFab 相同的取 Activity 方式）
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject?.reflekt()
                ?.firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                ?.get() as? Activity ?: return@hookAfter
            attachOverlay(activity)
        }

        // 已存在的主界面实例（如功能在运行时开启）
        runCatching {
            val launcher = LauncherUI::class.java.getMethod("getInstance").invoke(null) as? Activity
            if (launcher != null) attachOverlay(launcher)
        }

        hookTabsAdapter()
        hookChattingVisibility()
        hookSwipeGesture()

        overlays.values.forEach { it.setHomeTab(currentTab == 0) }
        WeLogger.i(TAG, "sidebar enabled")
    }

    override fun onDisable() {
        overlays.values.forEach { it.removeSelf() }
        overlays.clear()
        WeLogger.i(TAG, "sidebar disabled")
    }

    private fun attachOverlay(activity: Activity) {
        if (overlays.containsKey(activity)) return
        val overlay = SidebarOverlay(activity)
        (activity.window.decorView as? ViewGroup)?.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlays[activity] = overlay
        WeLogger.i(TAG, "sidebar overlay attached to " + activity.javaClass.simpleName)
    }

    private fun hookTabsAdapter() {
        val tabsAdapterClass = runCatching {
            Class.forName("com.tencent.mm.ui.MainTabUI\$TabsAdapter")
        }.getOrNull() ?: return
        val onPageSelected = runCatching {
            tabsAdapterClass.getDeclaredMethod("onPageSelected", Integer.TYPE).apply { makeAccessible() }
        }.getOrNull() ?: return
        onPageSelected.hookAfter {
            val position = args.getOrNull(0) as? Int ?: return@hookAfter
            currentTab = position
            overlays.values.forEach { it.setHomeTab(position == 0) }
        }
        WeLogger.i(TAG, "tab change hook installed")
    }

    private fun hookChattingVisibility() {
        runCatching {
            LauncherUI::class.java.getMethod(
                "startChatting",
                String::class.java,
                Bundle::class.java,
                java.lang.Boolean.TYPE
            )
        }.getOrNull()?.hookAfter {
            overlays.values.forEach { it.close() }
        }
        runCatching {
            LauncherUI::class.java.getMethod("closeChatting", java.lang.Boolean.TYPE)
        }.getOrNull()?.hookAfter {
            overlays.values.forEach { it.setHomeTab(currentTab == 0) }
        }
        WeLogger.i(TAG, "chat visibility hook installed")
    }

    private fun hookSwipeGesture() {
        val dispatchTouchEvent = runCatching {
            Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: return
        dispatchTouchEvent.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            val overlay = overlays[activity] ?: return@hookBefore
            val event = args.getOrNull(0) as? MotionEvent ?: return@hookBefore
            if (overlay.handleTouchEvent(event)) {
                result = true
            }
        }
        WeLogger.i(TAG, "swipe hook installed")
    }

    /** 侧滑栏 overlay：遮罩 + 左侧面板 + 内容区 3D 旋转联动（重建自 ym8.java）。 */
    private class SidebarOverlay(private val activity: Activity) : FrameLayout(activity) {

        private val density = resources.displayMetrics.density

        private var animating = false
        private var downX = 0f
        private var downY = 0f

        private val scrimView: View = View(context).apply {
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
            setOnClickListener { close() }
        }

        private val avatarContainer: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, (72 * density).toInt(), 0, 0)
            addView(TextView(context).apply {
                text = RuntimeConfig.loggedInWxId
                textSize = 16f
                setTextColor(0xFF111111.toInt())
            })
            addView(TextView(context).apply {
                text = "侧滑栏 · 实验性"
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            })
        }

        private val sidebarPanel: FrameLayout = FrameLayout(context).apply {
            visibility = View.GONE
            translationX = -(PANEL_WIDTH_DP * density)
            setBackgroundColor(0xFFF7F7F7.toInt())
            addView(
                avatarContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        init {
            addView(
                scrimView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                sidebarPanel,
                FrameLayout.LayoutParams(
                    (PANEL_WIDTH_DP * density).toInt(),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.START
                )
            )
        }

        val isOpen: Boolean
            get() = sidebarPanel.visibility == View.VISIBLE

        /** 处理边缘滑动手势，返回 true 表示已消费该事件（呼出侧滑栏）。 */
        fun handleTouchEvent(event: MotionEvent): Boolean {
            val edgePx = SWIPE_EDGE_DP * density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isOpen &&
                        downX in 0f..edgePx &&
                        event.x - downX > SWIPE_THRESHOLD_DP * density &&
                        abs(event.y - downY) < SWIPE_MAX_SLOP_DP * density
                    ) {
                        open()
                        return true
                    }
                }
            }
            return false
        }

        fun open() {
            if (animating || isOpen) return
            animating = true
            scrimView.visibility = View.VISIBLE
            avatarContainer.visibility = View.VISIBLE
            sidebarPanel.visibility = View.VISIBLE
            sidebarPanel.animate()
                .translationX(0f)
                .setDuration(250)
                .withEndAction { animating = false }
                .start()
            rotateContent(true)
        }

        fun close() {
            if (animating || !isOpen) return
            animating = true
            sidebarPanel.animate()
                .translationX(-sidebarPanel.width.toFloat())
                .setDuration(200)
                .withEndAction {
                    animating = false
                    scrimView.visibility = View.GONE
                    sidebarPanel.visibility = View.GONE
                }
                .start()
            rotateContent(false)
        }

        fun setHomeTab(home: Boolean) {
            if (home) {
                avatarContainer.visibility = View.VISIBLE
            }
        }

        /** 内容区 3D 旋转联动（重建自 ym8.a()）。 */
        private fun rotateContent(opening: Boolean) {
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            content.pivotX = 0f
            content.pivotY = content.height / 2f
            content.cameraDistance = density * 12000f / 3f
            content.animate()
                .rotationY(if (opening) -18f else 0f)
                .setDuration(250)
                .start()
        }

        fun removeSelf() {
            runCatching {
                (parent as? ViewGroup)?.removeView(this)
            }
            rotateContent(false)
        }
    }
}
