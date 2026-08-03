package io.github.skydynamic.maiproberplus.core.prober.lxns

import android.util.Log
import io.github.skydynamic.maiproberplus.Application.Companion.application
import io.github.skydynamic.maiproberplus.BuildConfig
import io.github.skydynamic.maiproberplus.core.prober.sendMessageToUi
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * 落雪查分器 OAuth 2.0 接入工具。
 *
 * 流程（无回调方案，redirect_uri = urn:ietf:wg:oauth:2.0:oob）：
 *  1. 用户在浏览器打开 [getAuthorizeUrl] 返回的授权链接，登录并授权；
 *  2. 落雪展示授权码（形如 JVJ6-VPTM-MGHZ），用户复制填回 App；
 *  3. App 调 [exchangeCodeForToken] 用授权码换 access_token / refresh_token，存进 ConfigStorage；
 *  4. 之后 API 调用前调 [ensureValidAccessToken]，过期则用 refresh_token 自动刷新；
 *     refresh_token 也失效（30 天）则清空并提示重新授权。
 *
 * client_secret 来自 BuildConfig（由一键构建脚本经 LXNS_OAUTH_CLIENT_SECRET 环境变量注入），
 * 不写入源码、不入仓库。
 */
object LxnsOAuthUtil {
    private const val TAG = "LxnsOAuthUtil"

    /** 无回调方案：授权后直接展示授权码，不重定向到任何地址。 */
    private const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    /** 申请的权限范围：读个人信息 + 读写玩家成绩。 */
    private const val SCOPE = "read_user_profile read_player write_player"

    private const val AUTHORIZE_URL = "https://maimai.lxns.net/oauth/authorize"
    private const val TOKEN_URL = "https://maimai.lxns.net/api/v0/oauth/token"

    /** access_token 提前刷新的缓冲（秒），避免临界过期时拿到就过期。 */
    private const val REFRESH_BUFFER_SECONDS = 60L

    private val json = Json { ignoreUnknownKeys = true }

    /** OAuth token 响应体（顶层字段，符合 OAuth 2.0 标准；旧版 data.* 已废弃）。 */
    @Serializable
    private data class TokenResponse(
        val access_token: String = "",
        val token_type: String = "",
        val expires_in: Int = 0,
        val refresh_token: String = "",
        val scope: String = ""
    )

    /** 错误响应体（扁平格式，不含 success/code/data）。 */
    @Serializable
    private data class TokenErrorResponse(
        val error: String = "",
        val error_description: String? = null
    )

    /** 拼好授权链接，用户在浏览器打开后登录并授权，拿到授权码填回 App。 */
    fun getAuthorizeUrl(): String {
        // response_type=code，无回调方案 redirect_uri=oob，scope 用空格分隔按文档示例。
        val scopeEncoded = SCOPE.replace(" ", "+")
        return "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${BuildConfig.LXNS_OAUTH_CLIENT_ID}" +
            "&redirect_uri=${REDIRECT_URI.replace(":", "%3A")}" +
            "&scope=$scopeEncoded"
    }

    /** 用授权码换 access_token / refresh_token，成功后存入 ConfigStorage。返回是否成功。 */
    suspend fun exchangeCodeForToken(code: String): Boolean {
        if (code.isBlank()) {
            sendMessageToUi("授权码不能为空")
            return false
        }
        val body = mapOf(
            "client_id" to BuildConfig.LXNS_OAUTH_CLIENT_ID,
            "client_secret" to BuildConfig.LXNS_OAUTH_CLIENT_SECRET,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI
        )
        return postTokenAndStore(body, hint = "授权码换令牌")
    }

    /**
     * 确保 access_token 仍有效：未过期直接返回；过期且有 refresh_token 则尝试刷新；
     * refresh_token 为空或刷新失败则返回 null，**不清空本地令牌、不主动提示重新授权**，
     * 避免给用户压力——只在 API 实际报错时由调用方提示重新绑定。
     */
    suspend fun ensureValidAccessToken(): String? {
        val cfg = application.configManager.config
        val now = System.currentTimeMillis()
        if (cfg.lxnsOAuthAccessToken.isNotEmpty() &&
            cfg.lxnsOAuthAccessTokenExpireAt > now + REFRESH_BUFFER_SECONDS * 1000
        ) {
            return cfg.lxnsOAuthAccessToken
        }
        // access_token 过期或即将过期，无 refresh_token 可刷新则返回 null（不清本地令牌）。
        if (cfg.lxnsOAuthRefreshToken.isBlank()) {
            return null
        }
        val body = mapOf(
            "client_id" to BuildConfig.LXNS_OAUTH_CLIENT_ID,
            "client_secret" to BuildConfig.LXNS_OAUTH_CLIENT_SECRET,
            "grant_type" to "refresh_token",
            "refresh_token" to cfg.lxnsOAuthRefreshToken
        )
        val ok = postTokenAndStore(body, hint = "刷新令牌")
        return if (ok) application.configManager.config.lxnsOAuthAccessToken else null
    }

    /** 清空本地 OAuth 令牌（用户取消授权时调用）。 */
    fun clearTokens() {
        val cfg = application.configManager.config
        cfg.lxnsOAuthAccessToken = ""
        cfg.lxnsOAuthRefreshToken = ""
        cfg.lxnsOAuthAccessTokenExpireAt = 0
        application.configManager.save()
    }

    /** 本地是否已有 OAuth 授权（有 refresh_token 即视为已授权过）。 */
    fun isAuthorized(): Boolean =
        application.configManager.config.lxnsOAuthRefreshToken.isNotEmpty()

    // ---- 内部 ----

    private suspend fun postTokenAndStore(
        body: Map<String, String>,
        hint: String
    ): Boolean {
        return try {
            val resp = io.github.skydynamic.maiproberplus.core.prober.client.post(TOKEN_URL) {
                contentType(ContentType.Application.Json)
                setBody(bodyToStringJson(body))
            }
            val respText = resp.bodyAsText()
            if (resp.status.value != 200) {
                val err = try { json.decodeFromString<TokenErrorResponse>(respText) } catch (_: Exception) { null }
                val msg = err?.let { "${it.error}${it.error_description?.let { d -> ": $d" } ?: ""}" } ?: respText
                Log.e(TAG, "$hint 失败: $msg")
                sendMessageToUi("${hint}失败: $msg")
                // invalid_grant（授权码过期 / refresh_token 失效）时不主动清空本地令牌，
                // 避免给用户压力；只在 API 实际报错时由调用方提示重新绑定。
                false
            } else {
                val token = json.decodeFromString<TokenResponse>(respText)
                val cfg = application.configManager.config
                cfg.lxnsOAuthAccessToken = token.access_token
                cfg.lxnsOAuthRefreshToken = token.refresh_token
                // expires_in 单位秒；存储 epoch ms 过期时刻，留 REFRESH_BUFFER_SECONDS 缓冲。
                cfg.lxnsOAuthAccessTokenExpireAt = System.currentTimeMillis() +
                    max(token.expires_in - REFRESH_BUFFER_SECONDS, 0L) * 1000
                application.configManager.save()
                Log.d(TAG, "$hint 成功，access_token 有效期 ${token.expires_in}s")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "$hint 异常: ${e.message}", e)
            sendMessageToUi("${hint}异常: ${e.message}")
            false
        }
    }

    private fun bodyToStringJson(body: Map<String, String>): String =
        body.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
}
