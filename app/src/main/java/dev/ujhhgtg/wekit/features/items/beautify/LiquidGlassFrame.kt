package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import kotlin.math.roundToInt

@Feature(
    name = "液态玻璃卡片",
    categories = ["界面美化"],
    description = "为会话列表等界面的列表项应用液态玻璃风格背景, 可调透明度/圆角/边距"
)
object LiquidGlassFrame : SwitchFeature(), IResolveDex {

    private const val TAG = "LiquidGlassFrame"

    private var opacity by prefOption("liquid_glass_opacity", 0.55f)
    private var cornerRadiusDp by prefOption("liquid_glass_radius", 16.0f)
    private var marginHorizontalDp by prefOption("liquid_glass_margin_h", 12.0f)

    private val exemptActivityNames = setOf(
        "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity",
        "com.tencent.mm.plugin.recordvideo.activity.MMRecordUI",
        "com.tencent.mm.plugin.fav.ui.detail.FavoriteImgDetailUI",
        "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI",
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI",
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryGridUI",
        "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI",
        "com.tencent.mm.plugin.subapp.ui.gallery.GestureGalleryUI",
        "com.tencent.mm.plugin.gallery.picker.view.ImageCropUI",
        "com.tencent.mm.plugin.sns.ui.SnsBrowseUI",
        "com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI",
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI",
        "com.tencent.mm.plugin.location_soso.SoSoProxyUI",
        "com.tencent.mm.plugin.finder.feed.ui.FinderProfileTimeLineUI",
        "com.tencent.mm.plugin.sns.ui.SnsGalleryUI",
        "com.tencent.mm.pluginsdk.ui.ProfileHdHeadImg",
        "com.tencent.mm.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI",
        "com.tencent.mm.plugin.voip.ui.VideoActivity"
    )

    private val originalTextColors = WeakHashMap<TextView, Int>()
    private var recyclerViewClass: Class<*>? = null

    override fun onEnable() {
        recyclerViewClass = runCatching {
            ClassLoaders.HOST.loadClass("androidx.recyclerview.widget.RecyclerView")
        }.getOrNull()

        AbsListView::class.reflekt()
            .firstMethodOrNull { name = "onLayout" }
            ?.hookBefore {
                applyGlassForListContainer()
            }
        recyclerViewClass?.reflekt()
            ?.firstMethodOrNull { name = "onLayout" }
            ?.hookBefore {
                applyGlassForListContainer()
            }
        AbsListView::class.java
            .getDeclaredMethod("obtainView", Int::class.javaPrimitiveType!!, BooleanArray::class.java)
            .hookAfter {
                applyGlassForItemView()
            }
        ViewGroup::class.java
            .getDeclaredMethod(
                "addView",
                View::class.java,
                Int::class.javaPrimitiveType!!,
                ViewGroup.LayoutParams::class.java
            )
            .hookAfter {
                applyGlassForItemView()
            }
        View::class.java
            .getDeclaredMethod("setBackgroundDrawable", Drawable::class.java)
            .hookBefore {
                wrapIncomingBackground()
            }
    }

    override fun onDisable() {
        synchronized(originalTextColors) {
            for ((textView, color) in originalTextColors) {
                runCatching { textView.setTextColor(color) }
            }
            originalTextColors.clear()
        }
    }

    // ------------------------------------------------------------------
    // hook 回调
    // ------------------------------------------------------------------

    private fun HookParam.applyGlassForListContainer() {
        val container = thisObject as? ViewGroup ?: return
        if (isExempt(container)) return
        applyGlass(container)
    }

    private fun HookParam.applyGlassForItemView() {
        val container = thisObject as? ViewGroup ?: return
        if (isExempt(container)) return
        val view = result as? View ?: return
        if (view.background is GlassFrameDrawable) return
        val threshold = (40f * view.resources.displayMetrics.density).toInt()
        if (view.height > 0 && view.height < threshold) return
        val context = view.context
        view.background?.let { original ->
            view.background = GlassFrameDrawable(
                buildGlassInsetDrawable(context, isDarkMode(context)),
                original.minimumWidth,
                original.minimumHeight
            )
        } ?: run {
            view.background = GlassFrameDrawable(buildGlassInsetDrawable(context, isDarkMode(context)), 0, 0)
        }
    }

    private fun HookParam.wrapIncomingBackground() {
        val view = thisObject as? View ?: return
        if (args[0] is GlassFrameDrawable) return
        val parent = view.parent as? ViewGroup ?: return
        val isListParent = parent is AbsListView || recyclerViewClass?.isInstance(parent) == true
        if (!isListParent || isExempt(parent)) return
        val threshold = (40f * view.resources.displayMetrics.density).toInt()
        if (view.height > 0 && view.height < threshold) return
        val original = args[0] as? Drawable
        val context = view.context
        args[0] = GlassFrameDrawable(
            buildGlassInsetDrawable(context, isDarkMode(context)),
            original?.minimumWidth ?: 0,
            original?.minimumHeight ?: 0
        )
    }

    // ------------------------------------------------------------------
    // 玻璃应用
    // ------------------------------------------------------------------

    private fun applyGlass(container: ViewGroup) {
        val density = container.resources.displayMetrics.density
        val threshold = (40f * density).toInt()
        if (container.javaClass.name.contains("TaskBar") ||
            container.javaClass.simpleName == "ConversationListView"
        ) {
            darkenTextViews(container)
        }
        for (child in container.children()) {
            if (child.height > 0 && child.height < threshold) continue
            if (child is ViewGroup && child.childCount > 3) {
                val tallGrandChildren = child.children().count { it.height > 0 && it.height >= threshold }
                if (tallGrandChildren > 2) {
                    for (grandChild in child.children()) {
                        if (grandChild.height > 0 && grandChild.height < threshold) continue
                        if (grandChild.background is GlassFrameDrawable) continue
                        val original = grandChild.background
                        val context = grandChild.context
                        grandChild.background = GlassFrameDrawable(
                            buildGlassInsetDrawable(context, isDarkMode(context)),
                            original?.minimumWidth ?: 0,
                            original?.minimumHeight ?: 0
                        )
                    }
                }
            }
            if (child.background is GlassFrameDrawable) continue
            val original = child.background
            val context = child.context
            child.background = GlassFrameDrawable(
                buildGlassInsetDrawable(context, isDarkMode(context)),
                original?.minimumWidth ?: 0,
                original?.minimumHeight ?: 0
            )
        }
    }

    private fun darkenTextViews(view: View) {
        if (view is TextView) {
            synchronized(originalTextColors) {
                if (!originalTextColors.containsKey(view)) {
                    originalTextColors[view] = view.currentTextColor
                }
            }
            view.setTextColor(Color.BLACK)
        }
        if (view is ViewGroup) {
            for (child in view.children()) darkenTextViews(child)
        }
    }

    private fun isExempt(viewGroup: ViewGroup): Boolean {
        var context: Context = viewGroup.context
        var activity: Activity? = null
        while (true) {
            if (context !is ContextWrapper) break
            if (context is Activity) {
                activity = context
                break
            }
            context = context.baseContext
        }
        if (activity != null) {
            val clazz = activity.javaClass
            val name = clazz.name
            if (exemptActivityNames.contains(name) ||
                name.contains("Chatting") ||
                name.contains("AppBrand") ||
                name.startsWith("com.tencent.mm.ui.chatting") ||
                name.startsWith("com.tencent.mm.plugin.appbrand")
            ) {
                return true
            }
        }
        if (viewGroup.tag == CustomizePlusMenu.MENU_TAG) return true
        val parentName = viewGroup.javaClass.name
        if (parentName.contains("Chatting") ||
            parentName.contains("AppBrand") ||
            parentName.contains("appbrand") ||
            parentName.contains("TaskBar")
        ) {
            return true
        }
        var current: View? = viewGroup
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("Chatting") ||
                name.contains("AppBrand") ||
                name.contains("appbrand") ||
                name.contains("TaskBar")
            ) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    // ------------------------------------------------------------------
    // 玻璃 Drawable
    // ------------------------------------------------------------------

    private fun buildGlassInsetDrawable(context: Context, isDark: Boolean): InsetDrawable {
        val density = context.resources.displayMetrics.density
        val cornerRadius = cornerRadiusDp * density
        val marginHorizontal = (marginHorizontalDp * density).roundToInt()
        val marginVertical = (3f * density).roundToInt()
        val glassDrawable = LiquidGlassDrawable(density, cornerRadius, opacity.coerceIn(0.1f, 0.95f), isDark)
        return InsetDrawable(glassDrawable, marginHorizontal, marginVertical, marginHorizontal, marginVertical)
    }

    private fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private class GlassFrameDrawable(
        private val inner: InsetDrawable,
        private val minWidth: Int,
        private val minHeight: Int
    ) : Drawable() {

        override fun draw(canvas: Canvas) {
            inner.draw(canvas)
        }

        override fun getMinimumHeight(): Int = minHeight

        override fun getMinimumWidth(): Int = minWidth

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            inner.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            inner.colorFilter = colorFilter
        }
    }

    private class LiquidGlassDrawable(
        density: Float,
        private val cornerRadius: Float,
        opacity: Float,
        private val isDark: Boolean
    ) : Drawable() {

        private val baseAlpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
        private val boundsRect = RectF()
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isDark) Color.argb(baseAlpha.coerceIn(0, 255), 40, 42, 54)
            else Color.argb(baseAlpha.coerceIn(0, 255), 255, 255, 255)
        }
        private val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bottomHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(density * 1.0f, 1.0f)
            color = if (isDark) Color.argb((baseAlpha * 0.6f).roundToInt().coerceIn(0, 255), 80, 84, 108)
            else Color.argb((baseAlpha * 0.7f).roundToInt().coerceIn(0, 255), 255, 255, 255)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, basePaint)
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, topHighlightPaint)
            if (!isDark) {
                canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, bottomHighlightPaint)
            }
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, borderPaint)
        }

        override fun getMinimumHeight(): Int = 0

        override fun getMinimumWidth(): Int = 0

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            boundsRect.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
            val height = bounds.height().toFloat()
            val topColor = if (isDark) Color.argb((baseAlpha * 0.35f).roundToInt().coerceIn(0, 255), 100, 105, 130)
            else Color.argb((baseAlpha * 0.45f).roundToInt().coerceIn(0, 255), 255, 255, 255)
            topHighlightPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(topColor, Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            if (isDark) return
            bottomHighlightPaint.shader = LinearGradient(
                0f, height, 0f, height * 0.6f,
                intArrayOf(Color.argb((baseAlpha * 0.2f).roundToInt().coerceIn(0, 255), 180, 180, 190), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun setAlpha(alpha: Int) {
            basePaint.alpha = alpha
            topHighlightPaint.alpha = alpha
            borderPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            basePaint.colorFilter = colorFilter
            topHighlightPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
        }
    }

    // ------------------------------------------------------------------
    // DexKit 目标 (用于宿主兼容性校验)
    // ------------------------------------------------------------------

    private val classChattingUI by dexClass {
        searchPackages("com.tencent.mm.ui.chatting")
        matcher {
            usingEqStrings("MicroMsg.ChattingUI")
            addInterface("com.tencent.mm.ui.chatting.ChattingUIFragment")
        }
    }

    private val classAppBrandUI by dexClass {
        searchPackages("com.tencent.mm.plugin.appbrand.ui")
        matcher {
            usingEqStrings("MicroMsg.AppBrandUI")
            superClass {
                className("com.tencent.mm.plugin.appbrand.ui.wxa_container.AppBrandContainerFragmentActivity")
            }
        }
    }

    private val classAppBrandLauncherUI by dexClass {
        searchPackages("com.tencent.mm.plugin.appbrand.ui")
        matcher {
            usingEqStrings("MicroMsg.AppBrandLauncherUI")
        }
    }
}
