package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    name = "绕过风险文件拦截",
    categories = ["聊天"],
    description = "让微信将文件一律判定为非风险文件, 跳过打开/下载文件时的风险提示"
)
object BypassRiskFileBlocking : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        methodRiskFileFlag.hookBefore {
            result = 0
        }
    }

    private val methodRiskFileFlag by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings(
                    "uuid",
                    "sfn",
                    "fold-reduce",
                    "show-h5",
                    "sec-ctrl-flag",
                    "clip-len",
                    "share-tip-url",
                    "media-to-emoji",
                    "block-range",
                    "bubble-type",
                    "preview-type",
                    "url-click-type",
                    "risk-file-flag",
                    "risk-file-md5-list",
                    "risk-warning-url",
                    "unread-media-expired"
                )
            }
            paramCount = 0
            returnType = "java.lang.Integer"
        }
    }
}
