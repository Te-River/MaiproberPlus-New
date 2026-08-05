package io.github.teriver.maiupload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.teriver.maiupload.ui.AppContent
import io.github.teriver.maiupload.ui.theme.MaiuploadTheme

val NOTIFICATION_CHANNEL_ID = "io.github.teriver.maiupload.notification.channel.default"
val PROCESS_NOTIFICATION_CHANNEL_ID = "io.github.teriver.maiupload.notification.channel.process"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaiuploadTheme(
                dynamicColor = false
            ) {
                AppContent()
            }
        }

        GlobalViewModel.localMessage.observe(this) { message ->
            // 累计未读提示到队列，避免弹窗单次显示吞后续提示
            GlobalViewModel.pendingMessages.add(message)
            GlobalViewModel.showMessageDialog = true
        }

        GlobalViewModel.needUpdate.observe(this) { needUpdate ->
            GlobalViewModel.showUpdateDialog = true
        }
    }
}
