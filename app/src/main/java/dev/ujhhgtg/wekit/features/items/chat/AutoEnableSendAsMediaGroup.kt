package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.widget.CheckBox
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

@Feature(
    name = "自动启用合并发送",
    categories = ["聊天"],
    description = "选中多张图片时自动勾选「合并发送」选项, 图片将以消息组的形式发送"
)
object AutoEnableSendAsMediaGroup : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        methodInitSendAsMediaGroupingViews.hookBefore {
            val activity = thisObject
            sendAsMediaGroupField.field.set(activity, true)
            (sendAsMediaGroupCheckBoxField.field.get(activity) as? CheckBox)?.setChecked(true)
        }
        methodUpdateSendAsMediaGroupViews.hookBefore {
            val count = args.getOrNull(0) as? Int ?: return@hookBefore
            if (count >= 3) {
                sendAsMediaGroupField.field.set(thisObject, true)
            }
        }
        classImagePreviewUI.hookBeforeOnCreate {
            (thisObject as? Activity)?.intent?.putExtra(EXTRA_SEND_AS_MEDIA_GROUP, true)
        }
    }

    private val classImagePreviewUI: Class<*> by lazy {
        ClassLoaders.HOST.loadClass("com.tencent.mm.plugin.gallery.ui.ImagePreviewUI")
    }

    private val methodInitSendAsMediaGroupingViews by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            usingStrings("initSendAsMediaGroupingViews")
        }
    }

    private val methodUpdateSendAsMediaGroupViews by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            usingStrings("updateSendAsMediaGroupViews")
        }
    }

    private val sendAsMediaGroupField by dexField {
        matcher {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            usingStrings("updateSendAsMediaGroupViews")
        }
    }

    private val sendAsMediaGroupCheckBoxField by dexField {
        matcher {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            type(CheckBox::class.java)
        }
    }

    private const val EXTRA_SEND_AS_MEDIA_GROUP = "key_send_as_media_group"
}
