package com.kail.location.views.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kail.location.R

/**
 * 游客次数用尽引导对话框。
 *
 * 游客的 [freeTotal] 次免费模拟机会用尽后弹出，引导用户登录/注册
 * （登录即自动注册，见 LoginScreen），并说明订阅会员可无限次使用。
 *
 * @param freeTotal 游客免费总次数（用于文案展示）。
 * @param onLogin 点击"登录 / 注册"：跳转登录页。
 * @param onDismiss 点击"稍后再说"或对话框外部：关闭引导。
 */
@Composable
fun GuestQuotaExhaustedDialog(
    freeTotal: Int,
    onLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guest_quota_exhausted_title)) },
        text = { Text(stringResource(R.string.guest_quota_exhausted_message, freeTotal)) },
        confirmButton = {
            TextButton(onClick = onLogin) {
                Text(stringResource(R.string.guest_quota_exhausted_login))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.guest_quota_exhausted_later))
            }
        }
    )
}
