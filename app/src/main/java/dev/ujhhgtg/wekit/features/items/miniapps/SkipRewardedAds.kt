package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

@Feature(
    name = "跳过激励视频广告",
    categories = ["小程序"],
    description = "自动完成小程序激励视频广告的奖励发放并立即关闭广告, 跳过观看过程"
)
object SkipRewardedAds : SwitchFeature(), IResolveDex {

    private const val TAG = "SkipRewardedAds"

    private val adAssetNames = listOf(
        "WAAppAd.js",
        "WAGameAd.js",
        "WASplashAdFloatWindow.js",
        "WASplashadWorker.js",
        "app-ad.js",
        "app-ad",
        "WAAd.js",
        "WASdkAd.js"
    )
    private val adEventKeywords = listOf("ad", "video", "reward", "close", "ended", "endpage")

    private val lastAdEventAt = AtomicLong(0L)

    override val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        ctorNetSceneJSOperateWxData.constructor.hookBefore {
            for (arg in args) {
                if (arg !is String) continue
                val json = runCatching { JSONObject(arg) }.getOrNull() ?: continue
                if (json.optString("api_name") == "webapi_getadvert") {
                    val data = runCatching { json.optJSONObject("data")?.toString() }.getOrNull()
                    WeLogger.i(TAG, "webapi_getadvert data=${data?.take(800)}")
                }
            }
        }
        listOf(methodPkgReaderH0, methodAssetReaderH0)
            .filter { !it.isPlaceholder }
            .forEach { delegate ->
                delegate.method.hookBefore {
                    val path = args.getOrNull(0) as? String ?: return@hookBefore
                    val lastSegment = path.substringAfterLast('/')
                    if (lastSegment in adAssetNames || lastSegment.startsWith("ad", ignoreCase = true)) {
                        WeLogger.i(TAG, "H0 blocked path=$path (force text inject)")
                        result = null
                    }
                }
            }
        methodInjectLibScript.hookBefore {
            injectPlayableAdSkip()
        }
        ClassLoaders.HOST.loadClass("com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding")
            .reflekt()
            .firstMethod { name = "subscribeHandler" }
            .hookBefore {
                logBridgeEvent()
            }
    }

    private fun HookParam.injectPlayableAdSkip() {
        val path = args.getOrNull(2) as? String ?: return
        val lastSegment = path.substringAfterLast('/')
        if (lastSegment !in adAssetNames && !lastSegment.startsWith("ad", ignoreCase = true)) return
        val script = args.getOrNull(6) as? String ?: return

        val ox = Regex("([A-Za-z_$][A-Za-z0-9_$]*)=\"PLAYABLE_FIRST_FRAME_READY\"")
            .find(script)?.groupValues?.getOrNull(1)
        if (ox == null) {
            warnSdkStructureChanged(path, script, "PLAYABLE_FIRST_FRAME_READY 常量")
            return
        }

        val rewardFn = Regex("([A-Za-z_$][A-Za-z0-9_$]*)=function\\(e,t=!1\\)\\{var a=[A-Za-z_$][A-Za-z0-9_$]*\\(this\\)")
            .findAll(script)
            .firstOrNull {
                val window = script.substring(it.range.first, minOf(it.range.first + 400, script.length))
                window.contains("isRewarded=!0") && window.contains("countDown")
            }?.groupValues?.getOrNull(1)
        if (rewardFn == null) {
            warnSdkStructureChanged(path, script, "置奖励函数 (function(e,t=!1)+isRewarded=!0+countDown)")
            return
        }

        val closeFn = Regex("([A-Za-z_$][A-Za-z0-9_$]*)=async function\\(e=0\\)\\{if\\([A-Za-z_$][A-Za-z0-9_$]*\\.call\\(this\\)\\)throw")
            .findAll(script)
            .firstOrNull {
                val window = script.substring(it.range.first, minOf(it.range.first + 6000, script.length))
                window.contains("lastAdIsEnded")
            }?.groupValues?.getOrNull(1)
        if (closeFn == null) {
            warnSdkStructureChanged(path, script, "关闭链路函数 (async function(e=0)+lastAdIsEnded)")
            return
        }

        val hasMbChannel = Regex("\\.mb=new [A-Za-z_$][A-Za-z0-9_$]*\\(").containsMatchIn(script) &&
            Regex("\\.mbId").containsMatchIn(script)
        if (!hasMbChannel) {
            warnSdkStructureChanged(path, script, "MB 通道实例字段 (.mb=new / .mbId)")
            return
        }

        val reqFn = Regex(
            "await ([A-Za-z_$][A-Za-z0-9_$]*)\\(\\{apiName:\"webapi_getadvert\",reqData:\\{action:\"weapp_comm\",request_data:JSON\\.stringify\\(\\{rpc_method:\"VerifyAdRewardEligibility\""
        ).find(script)?.groupValues?.getOrNull(1)

        val injection = buildString {
            append("setTimeout((()=>{try{var w=globalThis;w.__wekitPlayable=w.__wekitPlayable||{};")
            append("var id=t.adProxy?.data?.traceid;if(!w.__wekitPlayable[id]){w.__wekitPlayable[id]=1;")
            if (reqFn != null) {
                append(
                    "$reqFn({apiName:\"webapi_getadvert\",reqData:{action:\"weapp_comm\",request_data:JSON.stringify({rpc_method:\"WEKIT_PLAYABLE_CLOSE\",traceid:id})}});"
                )
            }
            append("var q=t.mb;if(q&&q.mbId){t.isEnded=!0,")
            append("$rewardFn.call(this,0,!0),Promise.resolve(")
            append("$closeFn.call(this)).catch(function(){}),q.destroy().catch(function(){})}}}catch(_){}}),200)")
        }

        val injectionPoint = Regex("emitter\\.emit\\(${Regex.escape(ox)}\\);break;")
        val injectionSites = injectionPoint.findAll(script).count()
        if (injectionSites == 0) {
            warnSdkStructureChanged(path, script, "首帧注入点 emitter.emit($ox);break;")
            return
        }
        WeLogger.i(
            TAG,
            "sdk patch symbols path=$path ox=$ox reward=$rewardFn close=$closeFn req=${reqFn ?: "N/A"} sites=$injectionSites"
        )

        val patched = injectionPoint.replace(script) { injection }
        if (patched == script) return
        args[6] = patched
        WeLogger.i(TAG, "inject SDK path=$path size=${script.length} delta=${patched.length - script.length}")
    }

    private fun HookParam.logBridgeEvent() {
        val type = args.getOrNull(0) as? String ?: return
        val data = args.getOrNull(1) as? String ?: ""
        val now = System.currentTimeMillis()
        val isAdEvent = type.startsWith("mbAd_") ||
            adEventKeywords.any { type.contains(it, ignoreCase = true) }
        val throttled = now <= lastAdEventAt.get()
        if (isAdEvent) {
            lastAdEventAt.set(now + 30_000)
        }
        if (!isAdEvent || throttled) {
            WeLogger.i(TAG, "bridge event type=$type data=${data.take(500)}")
        }
    }

    private fun warnSdkStructureChanged(path: String, script: String, missing: String) {
        WeLogger.w(TAG, "skip patch NOT applied path=$path missing=$missing (SDK 结构可能已改版) scriptLen=${script.length}")
    }

    private val ctorNetSceneJSOperateWxData by dexConstructor {
        searchPackages("com.tencent.mm.plugin.appbrand.utils")
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }

    private val methodInjectLibScript by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.utils")
        matcher {
            usingEqStrings("MicroMsg.JsValidationInjector", "hy: injecting file %s")
        }
    }

    private val methodPkgReaderH0 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            declaredClass {
                usingEqStrings("PkgReader[%d] [%s]")
            }
            paramTypes("java.lang.String")
            returnType = "android.content.res.AssetFileDescriptor"
        }
    }

    private val methodAssetReaderH0 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            declaredClass {
                usingEqStrings("AssetReader[%d][%s]")
            }
            paramTypes("java.lang.String")
            returnType = "android.content.res.AssetFileDescriptor"
        }
    }
}
