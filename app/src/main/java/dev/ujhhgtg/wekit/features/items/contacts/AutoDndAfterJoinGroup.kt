package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "进群自动开启消息免打扰",
    categories = ["联系人"],
    description = "当自己被拉入新的群聊时，自动为这个群开启消息免打扰"
)
object AutoDndAfterJoinGroup : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoDndAfterJoinGroup"

    private val pendingSyncs = ConcurrentHashMap<String, Boolean>()

    override fun onEnable() {
        methodSyncChatroomMembers.hookBefore {
            val chatroomId = args[0] as? String ?: return@hookBefore
            if (!chatroomId.isGroupChatWxId) return@hookBefore
            pendingSyncs[chatroomId] = isSelfInGroup(chatroomId)
        }
        methodSyncChatroomMembers.hookAfter {
            val chatroomId = args[0] as? String ?: return@hookAfter
            if (throwable != null) return@hookAfter
            val wasInGroup = pendingSyncs.remove(chatroomId) ?: return@hookAfter
            val nowInGroup = isSelfInGroup(chatroomId)
            if (!wasInGroup && nowInGroup) {
                if (!WeConversationApi.isDnd(chatroomId)) {
                    WeConversationApi.setDnd(chatroomId, true)
                    WeLogger.i(TAG, "joined new group $chatroomId, dnd enabled")
                }
            }
        }
    }

    private fun isSelfInGroup(chatroomId: String): Boolean {
        val selfWxId = RuntimeConfig.loggedInWxId
        if (selfWxId.isEmpty()) return false
        return runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT memberlist FROM chatroom WHERE chatroomname=?",
                arrayOf(chatroomId)
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@use false
                selfWxId in cursor.getString(0).split(';')
            }
        }.getOrDefault(false)
    }

    private val methodSyncChatroomMembers by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
            returnType = "boolean"
        }
    }
}
