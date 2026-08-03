package io.github.skydynamic.maiproberplus.ui.compose.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.skydynamic.maiproberplus.Application.Companion.application
import io.github.skydynamic.maiproberplus.GlobalViewModel
import io.github.skydynamic.maiproberplus.core.prober.rival.RivalSyncUtil

/**
 * 「Rival 设置」二级菜单页：从设置页「成绩抓取设置」大类的跳转按钮进入。
 * 装载类型一（Rival 同步）的全部配置输入框 + QR 鉴权入口。
 * 输入框标签只留字段名，不举示例，由用户自填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RivalSettingCompose(onBack: () -> Unit) {
    val rival = application.configManager.config.rivalSyncConfig

    var qrCodeInput by remember { mutableStateOf("") }
    var qrAuthing by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Rival 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "类型一（Rival 同步）配置。全部留空由你自填，不内置。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = rival.keychip,
                onValueChange = { rival.keychip = it; application.configManager.save() },
                singleLine = true,
                label = { Text("机台号", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.gameName,
                onValueChange = { rival.gameName = it; application.configManager.save() },
                singleLine = true,
                label = { Text("游戏名称", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.gameServerUrl,
                onValueChange = { rival.gameServerUrl = it; application.configManager.save() },
                singleLine = true,
                label = { Text("RivalApi 调用端口", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.authServerUrl,
                onValueChange = { rival.authServerUrl = it; application.configManager.save() },
                singleLine = true,
                label = { Text("Auth 鉴权节点", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.userId,
                onValueChange = { rival.userId = it; application.configManager.save() },
                singleLine = true,
                label = { Text("userId", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = qrCodeInput,
                onValueChange = { qrCodeInput = it },
                singleLine = true,
                label = { Text("QR 二维码内容", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (qrAuthing) return@Button
                    qrAuthing = true
                    GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val ok = RivalSyncUtil.authByQr(qrCodeInput.trim())
                            if (ok) qrCodeInput = ""
                        } finally {
                            withContext(Dispatchers.Main) { qrAuthing = false }
                        }
                    }
                },
                enabled = !qrAuthing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (qrAuthing) "鉴权中…" else "用 QR 二维码鉴权拿 userId")
            }

            OutlinedTextField(
                value = rival.cryptVersion,
                onValueChange = { rival.cryptVersion = it; application.configManager.save() },
                singleLine = true,
                label = { Text("加密版本", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.cryptKey,
                onValueChange = { rival.cryptKey = it; application.configManager.save() },
                singleLine = true,
                label = { Text("加密 Key", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.cryptIv,
                onValueChange = { rival.cryptIv = it; application.configManager.save() },
                singleLine = true,
                label = { Text("加密 IV", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.cryptEncoding,
                onValueChange = { rival.cryptEncoding = it; application.configManager.save() },
                singleLine = true,
                label = { Text("编码版本", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.cryptObfuscate,
                onValueChange = { rival.cryptObfuscate = it; application.configManager.save() },
                singleLine = true,
                label = { Text("混淆参数", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.authSalt,
                onValueChange = { rival.authSalt = it; application.configManager.save() },
                singleLine = true,
                label = { Text("Auth Salt", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.aesKey,
                onValueChange = { rival.aesKey = it; application.configManager.save() },
                singleLine = true,
                label = { Text("AES Key", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.aesIv,
                onValueChange = { rival.aesIv = it; application.configManager.save() },
                singleLine = true,
                label = { Text("AES IV", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rival.obfuscateParam,
                onValueChange = { rival.obfuscateParam = it; application.configManager.save() },
                singleLine = true,
                label = { Text("Obfuscate Param", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
