package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType

/**
 * 整数输入框：允许自由编辑（含清空/删字符），仅在解析成功且通过校验时提交。
 * 修复"onValueChange 只接受 toIntOrNull 成功值导致删不掉字符"的问题。
 */
@Composable
fun IntTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    validate: (Int) -> Boolean = { true },
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toIntOrNull()?.takeIf(validate)?.let(onValueChange)
        },
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        suffix = suffix,
        singleLine = singleLine,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
    )
}


/**
 * 可空整数输入框：空文本 = null（表示"未设置，用全局默认"），数字 = 提交值。
 */
@Composable
fun NullableIntTextField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            onValueChange(new.toIntOrNull())
        },
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        placeholder = placeholder,
        singleLine = singleLine,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
    )
}

/**
 * 官方 world_info_character_strategy 选择器：0=均匀 1=角色卡优先 2=全局优先
 */
@Composable
fun InsertionStrategySelector(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            0 to "均匀",
            1 to "角色卡优先",
            2 to "全局优先",
        ).forEach { (v, label) ->
            FilterChip(
                selected = selected == v,
                onClick = { onSelect(v) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}
