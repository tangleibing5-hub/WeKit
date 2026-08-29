package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Feature(
    name = "语音播放优化",
    categories = ["聊天"],
    description = "锁定语音消息的播放方式, 不被距离传感器自动切换听筒/扬声器"
)
object VoiceMessagePlaybackOptimization : ClickableFeature(), IResolveDex {

    private const val KEY_PLAYBACK_MODE = "voice_playback_mode"

    private const val MODE_EARPIECE = 0
    private const val MODE_SPEAKER = 1

    private var playbackMode by WePrefs.prefOption(KEY_PLAYBACK_MODE, MODE_SPEAKER)

    override fun onEnable() {
        methodSensorCallback.hookBefore {
            if (playbackMode == MODE_EARPIECE) {
                result = null
            }
        }
        methodSwitchTask.hookBefore {
            when (playbackMode) {
                MODE_EARPIECE -> result = false
                MODE_SPEAKER -> {
                    val autoPlay = findAutoPlay()
                    if (autoPlay != null) {
                        val speakerOn = findSpeakerOn()
                        methodSetSpeakerOn.method.invoke(autoPlay, speakerOn)
                        methodSwitchSpeaker.method.invoke(autoPlay, null)
                    }
                    result = false
                }
            }
        }
    }

    private fun findAutoPlay(): Any? {
        return thisObject?.reflekt()?.firstFieldOrNull { type = classAutoPlay.clazz }?.get()
    }

    private fun findSpeakerOn(): Boolean {
        return thisObject?.reflekt()?.firstFieldOrNull { type = Boolean::class.javaPrimitiveType }?.get() as? Boolean
            ?: false
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selected by remember { mutableStateOf(playbackMode) }
            AlertDialogContent(
                title = { Text("语音播放优化") },
                text = {
                    Column {
                        ModeRow("锁定听筒", "始终使用听筒播放, 禁用传感器自动切换", MODE_EARPIECE, selected) { selected = it }
                        ModeRow("锁定扬声器", "始终使用扬声器播放", MODE_SPEAKER, selected) { selected = it }
                    }
                },
                confirmButton = {
                    Button({
                        playbackMode = selected
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Composable
    private fun ModeRow(label: String, desc: String, value: Int, selected: Int, onSelect: (Int) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(value) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private val classAutoPlay by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
        }
    }

    private val methodSensorCallback by dexMethod {
        matcher {
            declaredClass = classAutoPlay.clazz.name
            usingStrings("onSensorEvent, isON:")
            paramTypes(Boolean::class.javaPrimitiveType!!)
            returnType = "void"
        }
    }

    private val methodSwitchTask by dexMethod {
        matcher {
            declaredClass = classAutoPlay.clazz.name
            returnType = "boolean"
            paramCount = 0
        }
    }

    private val methodSetSpeakerOn by dexMethod {
        matcher {
            declaredClass = classAutoPlay.clazz.name
            usingStrings("speakerOn has been set %s")
            paramTypes(Boolean::class.javaPrimitiveType!!)
        }
    }

    private val classSwitchSpeaker by dexClass {
        matcher {
            usingEqStrings("speaker true", "speaker off")
        }
    }

    private val methodSwitchSpeaker by dexMethod {
        matcher {
            declaredClass = classSwitchSpeaker.clazz.name
            usingStrings("switchSpeaker, isSpeakerOn: %b, isPlaying: %b")
            paramCount = 0
        }
    }
}
