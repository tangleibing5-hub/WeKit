package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.void
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 表情以图片打开。
 *
 * 基于 WePeak 1.7.0 行为重建。反编译（kca.java，show-bad-code 恢复）确认：
 *  - methodEmojiClickHandler: searchPackages("com.tencent.mm.ui.chatting.viewitems")
 *    + paramCount(1) + returnType(void) + usingEqStrings("MicroMsg.EmojiClickListener", "exit in teen mode")
 *  - methodEmojiClickEntry: declaredClass = handler 解析类 + paramTypes(View, null, null) + returnType(void)
 *  - hook（kca(14)）：点击贴纸消息时尝试微信解密路径，最终将图片用
 *    ShowImageUI（微信原生图片查看器）打开并拦截默认行为
 * 简化：省略解密/GIF 转换链路，直接快照贴纸 ImageView 当前画面存为 PNG，
 * 再用 ShowImageUI 打开，行为效果等价（贴纸以图片形式查看）。
 */
@Feature(
    name = "表情以图片打开",
    categories = ["聊天"],
    description = "聊天界面点击表情（贴纸）消息时，用微信原生图片查看器以图片方式打开"
)
object ViewStickerAsImage : SwitchFeature(), IResolveDex {

    private const val TAG = "ViewStickerAsImage"
    private const val MAX_DIMENSION = 2048
    private const val SNAPSHOT_DIR = "view-sticker-as-image/snapshots"

    private val methodEmojiClickHandler by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            paramCount(1)
            returnType(void)
            usingEqStrings("MicroMsg.EmojiClickListener", "exit in teen mode")
        }
    }

    private val methodEmojiClickEntry by dexMethod {
        matcher {
            declaredClass(methodEmojiClickHandler.method.declaringClass)
            paramCount(3)
            returnType(void)
        }
    }

    override fun onEnable() {
        methodEmojiClickEntry.hookBefore {
            val view = args.getOrNull(0) as? View ?: return@hookBefore
            val imagePath = snapshotSticker(view) ?: return@hookBefore
            val activity = view.context.findActivity() ?: return@hookBefore
            runCatching {
                val intent = Intent().apply {
                    setComponent(
                        ComponentName(activity.packageName, "com.tencent.mm.ui.tools.ShowImageUI")
                    )
                    putExtra("key_image_path", imagePath)
                }
                activity.startActivity(intent)
                result = null
            }.onFailure {
                WeLogger.e(TAG, "failed to open sticker in image viewer", it)
            }
        }
    }

    private fun snapshotSticker(view: View): String? {
        val imageView = view.findImageView() ?: return null
        val drawable = imageView.drawable ?: return null
        val width = if (imageView.width > 0) imageView.width else drawable.intrinsicWidth
        val height = if (imageView.height > 0) imageView.height else drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val scale = min(1.0, MAX_DIMENSION / max(width, height).toDouble())
        val bmpWidth = max(1, (width * scale).toInt())
        val bmpHeight = max(1, (height * scale).toInt())

        val cacheDir = File(view.context.cacheDir, SNAPSHOT_DIR).apply { mkdirs() }
        val file = File.createTempFile("sticker-preview-", ".png", cacheDir)
        return try {
            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(bmpWidth / width.toFloat(), bmpHeight / height.toFloat())
            if (imageView.width <= 0 || imageView.height <= 0) {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
            } else {
                imageView.draw(canvas)
            }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file.absolutePath
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to create sticker snapshot", e)
            file.delete()
            null
        }
    }

    private fun View.findImageView(): ImageView? {
        if (this is ImageView && drawable != null) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).findImageView()?.let { return it }
            }
        }
        return null
    }

    private fun Context.findActivity(): Activity? {
        var context: Context = this
        while (context is ContextWrapper) {
            if (context is Activity && !context.isFinishing && !context.isDestroyed) return context
            context = context.baseContext
        }
        return null
    }
}
