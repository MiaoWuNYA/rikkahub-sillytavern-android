package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.l10n
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.ui.components.OutlinedNumberInput
import me.rerere.ui.components.SelectTextField

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
            ) {
                SelectTextField(
                    value = when (setting) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Gemini -> "Gemini"
                        is TTSProviderSetting.SystemTTS -> "System TTS"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.Qwen -> "Qwen"
                        is TTSProviderSetting.Groq -> "Groq"
                        is TTSProviderSetting.XAI -> "xAI"
                        is TTSProviderSetting.MiMo -> "MiMo"
                        is TTSProviderSetting.Step -> "Step"
                        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                        is TTSProviderSetting.FishAudio -> "Fish Audio"
                        is TTSProviderSetting.Doubao -> l10n("火山引擎", "Volcano Engine")
                    },
                    options = TTSProviderSetting.Types,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { providerClass ->
                        when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> "OpenAI"
                            TTSProviderSetting.Gemini::class -> "Gemini"
                            TTSProviderSetting.SystemTTS::class -> "System TTS"
                            TTSProviderSetting.MiniMax::class -> "MiniMax"
                            TTSProviderSetting.Qwen::class -> "Qwen"
                            TTSProviderSetting.Groq::class -> "Groq"
                            TTSProviderSetting.XAI::class -> "xAI"
                            TTSProviderSetting.MiMo::class -> "MiMo"
                            TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                            TTSProviderSetting.FishAudio::class -> "Fish Audio"
                            TTSProviderSetting.Doubao::class -> l10n("火山引擎", "Volcano Engine")
                            TTSProviderSetting.Step::class -> "Step"
                            else -> providerClass.simpleName ?: "Unknown"
                        }
                    },
                    onOptionSelected = { providerClass ->
                        val newSetting = when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                                id = setting.id,
                                name = "OpenAI TTS"
                            )

                            TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                                id = setting.id,
                                name = "Gemini TTS"
                            )

                            TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                                id = setting.id,
                                name = "System TTS"
                            )

                            TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                                id = setting.id,
                                name = "MiniMax TTS"
                            )

                            TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                                id = setting.id,
                                name = "Qwen TTS"
                            )

                            TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                                id = setting.id,
                                name = "Groq TTS"
                            )

                            TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                                id = setting.id,
                                name = "xAI TTS"
                            )

                            TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                                id = setting.id,
                                name = "MiMo TTS"
                            )

                            TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(
                                id = setting.id,
                                name = "ElevenLabs TTS"
                            )

                            TTSProviderSetting.FishAudio::class -> TTSProviderSetting.FishAudio(
                                id = setting.id,
                                name = "Fish Audio TTS"
                            )

                            TTSProviderSetting.Step::class -> TTSProviderSetting.Step(
                                id = setting.id,
                                name = "Step TTS"
                            )

                            TTSProviderSetting.Doubao::class -> TTSProviderSetting.Doubao(id = setting.id)

                            else -> setting
                        }
                        onValueChange(newSetting)
                    }
                )
            }

            // Name
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_name)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_name_description)) }
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { newName ->
                        onValueChange(setting.copyProvider(name = newName))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
                )
            }
        }

        CardGroup {
            when (setting) {
                is TTSProviderSetting.OpenAI -> openAITTSItems(setting, onValueChange)
                is TTSProviderSetting.Gemini -> geminiTTSItems(setting, onValueChange)
                is TTSProviderSetting.MiniMax -> miniMaxTTSItems(setting, onValueChange)
                is TTSProviderSetting.SystemTTS -> systemTTSItems(setting, onValueChange)
                is TTSProviderSetting.Qwen -> qwenTTSItems(setting, onValueChange)
                is TTSProviderSetting.Groq -> groqTTSItems(setting, onValueChange)
                is TTSProviderSetting.XAI -> xAITTSItems(setting, onValueChange)
                is TTSProviderSetting.MiMo -> miMoTTSItems(setting, onValueChange)
                is TTSProviderSetting.ElevenLabs -> elevenLabsTTSItems(setting, onValueChange)
                is TTSProviderSetting.FishAudio -> fishAudioTTSItems(setting, onValueChange)
                is TTSProviderSetting.Step -> stepTTSItems(setting, onValueChange)
                is TTSProviderSetting.Doubao -> doubaoTTSItems(setting, onValueChange)
            }
        }
    }
}

private fun CardGroupScope.openAITTSItems(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.miMoTTSItems(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // MiMo 配置均为自由输入 默认值只是占位
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2-tts") }
        )
    }

    // Voice
    val voices = listOf(
        "mimo_default",
        "冰糖",
        "茉莉",
        "苏打",
        "白桦",
        "Mia",
        "Chloe",
        "Milo",
        "Dean"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo_default") }
        )
    }
}

private fun CardGroupScope.miniMaxTTSItems(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.8-hd") }
        )
    }

    // Voice ID
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voiceIds,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { voiceId ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

private fun CardGroupScope.geminiTTSItems(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    // Voice Name
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder)) }
        )
    }
}

private fun CardGroupScope.systemTTSItems(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

private fun CardGroupScope.qwenTTSItems(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    val models = listOf(
        "qwen-audio-3.0-tts-flash",
        "qwen-audio-3.0-tts-plus",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { model ->
                val defaultVoice = when (model) {
                    "qwen-audio-3.0-tts-plus" -> "longanlingxin"
                    "qwen-audio-3.0-tts-flash" -> "longanhuan_v3.6"
                    else -> setting.voice
                }
                onValueChange(setting.copy(model = model, voice = defaultVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen-audio-3.0-tts-flash") }
        )
    }

    // Voice
    val voices = when (setting.model) {
        "qwen-audio-3.0-tts-plus" -> listOf("longanlingxin", "longanlufeng")
        "qwen-audio-3.0-tts-flash" -> listOf(
            "longanfengyue", "longanyuanfei", "longanlingxi", "longanxiaoxin",
            "longanhuan_v3.6", "longjielidou_v3.6", "longpaopao_v3.6",
            "longhuohuo_v3.6", "longchuanshu_v3.6", "loongmary",
            "loongeva_v3.6", "loongjohn",
        )

        else -> emptyList()
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Audio Format
    val formats = listOf("wav", "mp3", "pcm", "opus")

    formItem(
        headlineContent = { Text("Audio Format") },
        supportingContent = { Text("Audio encoding returned by Qwen TTS") }
    ) {
        SelectTextField(
            value = setting.format,
            options = formats,
            readOnly = true,
            onOptionSelected = { format ->
                onValueChange(setting.copy(format = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000, 44100, 48000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text("Audio sample rate in Hz") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { sampleRate ->
                onValueChange(setting.copy(sampleRate = sampleRate))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.groqTTSItems(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    val voices = listOf("austin", "natalie", "kailin")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.xAITTSItems(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Language
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    formItem(
        headlineContent = { Text("Language") },
    ) {
        SelectTextField(
            value = setting.language,
            options = languages,
            onValueChange = { newLanguage ->
                onValueChange(setting.copy(language = newLanguage))
            },
            onOptionSelected = { (code, _) ->
                onValueChange(setting.copy(language = code))
            },
            optionToString = { (code, displayName) -> "$displayName ($code)" },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.elevenLabsTTSItems(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_...") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    // Model
    val models = listOf(
        "eleven_multilingual_v2" to "Eleven Multilingual v2",
        "eleven_v3" to "Eleven v3",
        "eleven_flash_v2_5" to "Eleven Flash v2.5"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") }
        )
    }

    // Stability
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_stability)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_stability_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

private fun CardGroupScope.fishAudioTTSItems(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://fish.audio/app/api-keys") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") }
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    val models = listOf(
        "s2.1-pro" to "S2.1-Pro (推荐)",
        "s2.1-pro-free" to "S2.1-Pro Free (免费)",
        "s2-pro" to "S2-Pro",
        "s1" to "S1"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID (reference_id)
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") }
        )
    }

    // Temperature
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_temperature_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

private fun CardGroupScope.stepTTSItems(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.tts_step_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_step_api_key_placeholder)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    val models = listOf(
        "step-tts-mini" to "step-tts-mini (轻量, 便宜)",
        "step-tts-vivid" to "step-tts-vivid (情感丰富)",
        "stepaudio-2.5-tts" to "stepaudio-2.5-tts (语境感知, 支持 instruction)",
        "step-tts-2" to "step-tts-2 (上一代)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to "气质温婉 (elegantgentle-female)",
        "livelybreezy-female" to "活力轻快 (livelybreezy-female)",
        "energeticconfident-female" to "活力自信 (energeticconfident-female)",
        "jingdiannvsheng" to "经典女声 (jingdiannvsheng)",
        "wenroushunv" to "温柔熟女 (wenroushunv)",
        "tianmeinvsheng" to "甜美女声 (tianmeinvsheng)",
        "qingchunshaonv" to "清纯少女 (qingchunshaonv)",
        "wenrounvsheng" to "温柔女声 (wenrounvsheng)",
        "ruanmengnvsheng" to "软萌女生 (ruanmengnvsheng)",
        "youyanvsheng" to "优雅女生 (youyanvsheng)",
        "lengyanyujie" to "冷艳御姐 (lengyanyujie)",
        "shuangkuaijiejie" to "爽快姐姐 (shuangkuaijiejie)",
        "wenjingxuejie" to "文静学姐 (wenjingxuejie)",
        "linjiajiejie" to "邻家姐姐 (linjiajiejie)",
        "linjiameimei" to "邻家妹妹 (linjiameimei)",
        "zhixingjiejie" to "知性姐姐 (zhixingjiejie)",
        "cixingnansheng" to "磁性男声 (cixingnansheng)",
        "wenrounansheng" to "温柔男声 (wenrounansheng)",
        "yuanqinansheng" to "元气男声 (yuanqinansheng)",
        "zhengpaiqingnian" to "正派青年 (zhengpaiqingnian)",
        "ruyananshi" to "儒雅男士 (ruyananshi)",
        "boyinnansheng" to "播音男声 (boyinnansheng)",
        "shenchennanyin" to "深沉男音 (shenchennanyin)",
        "shuangkuainansheng" to "爽快男声 (shuangkuainansheng)",
        "ganliannvsheng" to "干练女声 (ganliannvsheng)",
        "qinhenvsheng" to "亲切女声 (qinhenvsheng)",
        "huolinvsheng" to "活力女声 (huolinvsheng)",
        "jilingshaonv" to "机灵少女 (jilingshaonv)",
        "yuanqishaonv" to "元气少女 (yuanqishaonv)",
        "wenrougongzi" to "温柔公子 (wenrougongzi)",
        "qingniandaxuesheng" to "青年大学生 (qingniandaxuesheng)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voice = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Response Format
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    formItem(
        headlineContent = { Text("Response Format") },
        supportingContent = { Text(stringResource(R.string.tts_step_response_format_desc)) }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(responseFormat = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(responseFormat = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.tts_step_speed_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    formItem(
        headlineContent = { Text("Volume") },
        supportingContent = { Text(stringResource(R.string.tts_step_volume_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text(stringResource(R.string.tts_sample_rate_desc)) }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    formItem(
        headlineContent = { Text("Instruction") },
        supportingContent = { Text(stringResource(R.string.tts_step_instruction_desc)) }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_step_instruction_placeholder)) },
            minLines = 2,
            maxLines = 4,
        )
    }
}

private fun CardGroupScope.doubaoTTSItems(
    setting: TTSProviderSetting.Doubao,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.tts_doubao_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("ark-xxx") },
        )
    }

    // Base URL (完整接口地址)
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.tts_doubao_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional") }
        )
    }

    // Resource ID
    // 预设只列 seed-tts-2.0：Agent Plan Key 只开通 2.0，选 1.0 会报
    // 45000030 [resource_id=volc.service_type.10029] not granted；
    // 标准控制台 1.0 用户可手动输入
    formItem(
        headlineContent = { Text("Resource ID") },
        supportingContent = { Text(stringResource(R.string.tts_doubao_resource_id_desc)) }
    ) {
        SelectTextField(
            value = setting.resourceId,
            options = listOf("seed-tts-2.0"),
            onValueChange = { newId ->
                onValueChange(setting.copy(resourceId = newId))
            },
            onOptionSelected = { newId ->
                onValueChange(setting.copy(resourceId = newId))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speaker (音色)
    // 2.0 音色 (_uranus/_saturn_bigtts) 配 seed-tts-2.0, 1.0 音色 (_mars_bigtts) 配 seed-tts-1.0
    val speakers = listOf(
        "zh_male_kuailexiaodong_uranus_bigtts" to "快乐小东 2.0 (豆包同款)",
        "zh_female_cancan_uranus_bigtts" to "灿灿 2.0",
        "zh_female_tianmeitaozi_uranus_bigtts" to "甜美桃子 2.0",
        "zh_female_tianmeixiaoyuan_uranus_bigtts" to "甜美小媛 2.0",
        "zh_female_qingxinnvsheng_uranus_bigtts" to "清新女声 2.0",
        "zh_female_gaolengyujie_uranus_bigtts" to "高冷御姐 2.0",
        "zh_female_shuangkuaisisi_uranus_bigtts" to "爽快思思 2.0",
        "zh_female_sajiaoxuemei_uranus_bigtts" to "撒娇学妹 2.0",
        "zh_female_qiaopinv_uranus_bigtts" to "俏皮贫女 2.0",
        "zh_female_popo_uranus_bigtts" to "婆婆 2.0",
        "zh_female_peiqi_uranus_bigtts" to "佩琪 2.0",
        "zh_female_sophie_uranus_bigtts" to "Sofie 2.0",
        "zh_female_jitangnv_uranus_bigtts" to "鸡汤女 2.0",
        "zh_female_mizai_saturn_bigtts" to "米仔 2.0",
        // 1.0 音色 (_mars_bigtts) 需把 Resource ID 改成 seed-tts-1.0 后手动输入，预设不再列出，
        // 避免与默认 seed-tts-2.0 混用报 55000000
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.tts_doubao_speaker_desc)) }
    ) {
        OutlinedTextField(
            value = setting.speaker,
            onValueChange = { newSpeaker ->
                onValueChange(setting.copy(speaker = newSpeaker))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh_female_cancan_uranus_bigtts") },
            supportingText = { Text(speakers.firstOrNull { it.first == setting.speaker }?.second.orEmpty()) }
        )
    }

    // Format
    val formats = listOf("mp3", "wav", "pcm", "ogg", "opus")

    formItem(
        headlineContent = { Text("Audio Format") },
        supportingContent = { Text(stringResource(R.string.tts_audio_format_desc)) }
    ) {
        SelectTextField(
            value = setting.format,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(format = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(format = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text(stringResource(R.string.tts_sample_rate_desc)) }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speech Rate
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        supportingContent = { Text(stringResource(R.string.tts_doubao_speech_rate_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.2f..2.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }
}
