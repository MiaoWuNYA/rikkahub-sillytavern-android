package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.text.KeyboardOptions
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
