package dev.ujhhgtg.wekit.features.items.beautify

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "「+」菜单定制",
    categories = ["界面美化"],
    description = "自定义聊天输入栏「+」面板菜单的图标与文字颜色, 可设置背景图 (files/plus_menu_bg.png)"
)
object CustomizePlusMenu : ClickableFeature(), IResolveDex {

    const val MENU_TAG = -1374429230

    private var textLight by WePrefs.prefOption("plus_menu_text_light", "#000000")
    private var textDark by WePrefs.prefOption("plus_menu_text_dark", "#ffffff")
    private var bgLight by WePrefs.prefOption("plus_menu_bg_light", "#00000000")
    private var bgDark by WePrefs.prefOption("plus_menu_bg_dark", "#00000000")

    private val hookedGetViewMethods = ConcurrentHashMap.newKeySet<java.lang.reflect.Method>()
    private val memoizedBg = ConcurrentHashMap<String, BitmapDrawable>()

    override fun onEnable() {
        methodAddItem.hookAfter { param ->
            val host = param.thisObject
            if (host == null) return@hookAfter
            var listViewApplied = false
            host.javaClass.declaredFields.forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(host) }.getOrNull() ?: return@forEach
                if (!listViewApplied && value is ListView) {
                    applyBackground(value)
                    listViewApplied = true
                }
                if (value is BaseAdapter) {
                    val getView = value.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 3 }
                    if (getView != null && hookedGetViewMethods.add(getView)) {
                        getView.hookAfter { adapterParam ->
                            val view = adapterParam.result as? View ?: return@hookAfter
                            applyMenuColors(view)
                            view.post { applyMenuColors(view) }
                        }
                    }
                }
            }
        }
    }

    private fun applyBackground(view: View) {
        view.setTag(MENU_TAG)
        val dark = (view.context.resources.configuration.uiMode and 48) == 32
        val bgColor = parseColor(if (dark) bgDark else bgLight)
        var bgImage: BitmapDrawable? = null
        val file = File(view.context.filesDir, PLUS_MENU_BG_FILE)
        if (file.exists()) {
            val key = "${file.lastModified()}:${file.length()}"
            bgImage = memoizedBg[key] ?: runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.let { BitmapDrawable(view.context.resources, it) }
            }.getOrNull()?.also { memoizedBg[key] = it }
        }
        WeLogger.i(TAG, "applyBackgroundToContainer: ${view.javaClass.name} bgColor=$bgColor bgImg=$bgImage")
        when {
            bgImage != null -> view.background = bgImage
            bgColor != 0 -> view.background = ColorDrawable(bgColor)
        }
    }

    private fun applyMenuColors(view: View) {
        val dark = (view.context.resources.configuration.uiMode and 48) == 32
        val color = parseColor(if (dark) textDark else textLight)
        if (color == 0) return
        collectChildren<TextView>(view).forEach { it.setTextColor(color) }
        collectChildren<ImageView>(view).forEach { imageView ->
            imageView.drawable?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    private inline fun <reified T : View> collectChildren(root: View): List<T> {
        val result = mutableListOf<T>()
        if (root is T) result.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(collectChildren(root.getChildAt(i)))
            }
        }
        return result
    }

    private fun parseColor(value: String): Int {
        if (value.isBlank()) return 0
        return runCatching { Color.parseColor(value) }.getOrDefault(0)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var newTextLight by remember { mutableStateOf(textLight) }
            var newTextDark by remember { mutableStateOf(textDark) }
            var newBgLight by remember { mutableStateOf(bgLight) }
            var newBgDark by remember { mutableStateOf(bgDark) }
            AlertDialogContent(
                title = { Text("「+」菜单定制") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        ColorField("浅色文字色", newTextLight) { newTextLight = it }
                        ColorField("深色文字色", newTextDark) { newTextDark = it }
                        ColorField("浅色背景色", newBgLight) { newBgLight = it }
                        ColorField("深色背景色", newBgDark) { newBgDark = it }
                        Text(
                            "背景图: 将 plus_menu_bg.png 放入 files 目录",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    Button({
                        textLight = newTextLight
                        textDark = newTextDark
                        bgLight = newBgLight
                        bgDark = newBgDark
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Composable
    private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
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

    private val methodAddItem by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null, we use default one")
        }
    }

    private const val PLUS_MENU_BG_FILE = "plus_menu_bg.png"
}
