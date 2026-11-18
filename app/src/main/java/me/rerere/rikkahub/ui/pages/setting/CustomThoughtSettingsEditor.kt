package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.CustomThoughtRule
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.ui.components.Switch

@Composable
fun CustomThoughtSettingsEditor(
    display: DisplaySetting,
    onUpdate: ((DisplaySetting) -> DisplaySetting) -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text("自定义思考链（肘击思考链）") },
    ) {
        item(
            headlineContent = { Text("隐藏原生思考窗") },
            supportingContent = { Text("仅隐藏原生 Reasoning 显示，不修改推理数据、工具调用或回传设置。") },
            trailingContent = {
                Switch(modifier = Modifier.testTag("hide-native-reasoning"), checked = display.hideNativeReasoning, onCheckedChange = { checked ->
                    onUpdate { it.copy(hideNativeReasoning = checked) }
                })
            },
        )
        item(
            headlineContent = { Text("启用自定义思考链解析") },
            supportingContent = { Text("所有角色共享；只提取剩余正文中最先出现的完整非空标签对。") },
            trailingContent = {
                Switch(modifier = Modifier.testTag("enable-custom-thought"), checked = display.customThought.enabled, onCheckedChange = { checked ->
                    onUpdate { it.copy(customThought = it.customThought.copy(enabled = checked)) }
                })
            },
        )
        item(
            headlineContent = { Text("不作为正文回传") },
            supportingContent = { Text("解析开启时从请求副本移除匹配片段；原始正文和原生推理不变。") },
            trailingContent = {
                Switch(modifier = Modifier.testTag("exclude-custom-thought"), checked = display.customThought.excludeFromRequests, onCheckedChange = { checked ->
                    onUpdate { it.copy(customThought = it.customThought.copy(excludeFromRequests = checked)) }
                })
            },
        )
        display.customThought.rules.forEach { rule ->
            formItem {
                key(rule.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("标签规则", modifier = Modifier.weight(1f))
                            Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                                onUpdate { current -> current.updateThoughtRule(rule.id) { it.copy(enabled = enabled) } }
                            })
                            TextButton(onClick = {
                                onUpdate { current -> current.copy(customThought = current.customThought.copy(
                                    rules = current.customThought.rules.filterNot { it.id == rule.id },
                                )) }
                            }) { Text("删除规则") }
                        }
                        OutlinedTextField(
                            value = rule.startTag,
                            onValueChange = { value ->
                                onUpdate { current -> current.updateThoughtRule(rule.id) { it.copy(startTag = value) } }
                            },
                            label = { Text("开始标签") },
                            placeholder = { Text("<rainchain>") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = rule.endTag,
                            onValueChange = { value ->
                                onUpdate { current -> current.updateThoughtRule(rule.id) { it.copy(endTag = value) } }
                            },
                            label = { Text("结束标签") },
                            placeholder = { Text("</rainchain>") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (rule.startTag.isBlank() || rule.endTag.isBlank()) Text("填写两个标签后生效，按字面量区分大小写匹配。")
                    }
                }
            }
        }
        formItem {
            TextButton(onClick = {
                onUpdate { it.copy(customThought = it.customThought.copy(
                    rules = it.customThought.rules + CustomThoughtRule(),
                )) }
            }) { Text("新增规则") }
        }
    }
}

private fun DisplaySetting.updateThoughtRule(id: String, update: (CustomThoughtRule) -> CustomThoughtRule): DisplaySetting =
    copy(customThought = customThought.copy(rules = customThought.rules.map { if (it.id == id) update(it) else it }))
