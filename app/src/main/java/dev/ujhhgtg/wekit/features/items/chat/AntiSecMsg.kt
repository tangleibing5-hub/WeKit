package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    name = "反安全消息",
    categories = ["聊天"],
    description = "让微信忽略消息中的安全标记(sec_msg_node), 解除安全消息的部分操作限制"
)
object AntiSecMsg : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        methodRawSfnCheck.hookBefore {
            result = false
        }
    }

    private val methodRawSfnCheck by dexMethod {
        matcher {
            usingEqStrings(".msgsource.sec_msg_node.sfn")
            returnType = "boolean"
            paramCount = 1
        }
    }
}
