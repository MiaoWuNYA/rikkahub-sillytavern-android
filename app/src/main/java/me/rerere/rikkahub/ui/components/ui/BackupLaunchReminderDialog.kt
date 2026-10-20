package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.rikkahub.R

/** 每启动这么多次提醒一次备份 */
const val BACKUP_REMINDER_LAUNCH_INTERVAL = 15

/**
 * 启动次数触发的备份提醒。
 *
 * 目的只是养成习惯：连续打开几十次却从没备份过的用户，一旦数据丢失
 * 丢的是全部会话、助手和角色卡。这里在跨过整数倍启动次数时弹一次，
 * 用户勾上「不再提醒」后永久关闭。
 */
@Composable
fun BackupLaunchReminderDialog(
    launchCount: Int,
    onDismiss: (dontRemindAgain: Boolean) -> Unit,
    onBackupNow: (dontRemindAgain: Boolean) -> Unit,
) {
    var dontRemindAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss(dontRemindAgain) },
        icon = {
            Icon(
                imageVector = HugeIcons.DatabaseRestore,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.backup_launch_reminder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_launch_reminder_message, launchCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontRemindAgain,
                        onCheckedChange = { dontRemindAgain = it },
                    )
                    Text(
                        text = stringResource(R.string.backup_launch_reminder_dont_remind),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            // 「立即备份」只负责把用户带到备份界面，不直接开跑：备份方式、目标目录
            // 都要用户自己选，弹窗里替他决定等于替他选错
            Button(onClick = { onBackupNow(dontRemindAgain) }) {
                Text(stringResource(R.string.backup_launch_reminder_backup_now))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(dontRemindAgain) }) {
                Text(stringResource(R.string.backup_launch_reminder_got_it))
            }
        },
    )
}
