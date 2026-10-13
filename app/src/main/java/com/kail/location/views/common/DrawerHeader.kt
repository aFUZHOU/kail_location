package com.kail.location.views.common

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kail.location.R
import com.kail.location.auth.AuthManager
import com.kail.location.auth.GuestModeManager

/**
 * Composable function for the navigation drawer header.
 * Displays app icon, name, version, and a tip message.
 *
 * 登录体系 UI 隐藏时（[AuthManager.LOGIN_UI_ENABLED] = false）：
 * 头部不可点击，且不展示登录态邮箱，统一显示游客标签。
 *
 * @param version The app version string to display.
 */
@Composable
fun DrawerHeader(version: String, onLoginClick: (() -> Unit)? = null, onProfileClick: (() -> Unit)? = null) {
    val isLoggedIn by AuthManager.isLoggedInState
    val userEmail by AuthManager.emailState
    val loginUiEnabled = AuthManager.LOGIN_UI_ENABLED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = loginUiEnabled) {
                if (isLoggedIn) onProfileClick?.invoke()
                else onLoginClick?.invoke()
            }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        setImageResource(R.mipmap.ic_launcher_round)
                    }
                },
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (loginUiEnabled && isLoggedIn) {
                    Text(
                        text = userEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    // 游客模式：展示剩余免费次数（-1 表示无限次）
                    val guestRemaining by GuestModeManager.guestRemainingState
                    Text(
                        text = when {
                            guestRemaining < 0 -> stringResource(R.string.guest_mode_unlimited)
                            else -> stringResource(R.string.guest_mode_remaining, guestRemaining)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_msg),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.nav_app_tips),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
