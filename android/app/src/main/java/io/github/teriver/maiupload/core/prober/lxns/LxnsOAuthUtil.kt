package io.github.teriver.maiupload.core.prober.lxns

import android.util.Base64
import io.github.teriver.maiupload.Application.Companion.application
import io.github.teriver.maiupload.BuildConfig
import io.github.teriver.maiupload.core.prober.sendMessageToUi
import io.github.teriver.maiupload.core.utils.DebugLog
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.max

/**
 * 落雪查分器 OAuth 2.0 + PKCE 接入工具（公共客户端，无 client_secret）。
 *
 * 流程（无回调方案，redirect_uri = urn:ietf:wg:oauth:2.0:oob）：
 *  1. [getAuthorizeUrl] 生成随机 code_verifier 并存入 ConfigStorage，
 *     计算 code_challenge (S256)，拼好带 PKCE 参数的授权链接；
 *  2. 用户在浏览器授权，落雪展示授权码（形如 JVJ6-VPTM-MGHZ），用户复制填回 App；
 *  3. [exchangeCodeForToken] 用 授权码 + code_verifier 换 access_token / refresh_token；
 *  4. 之后 API 调用前调 [ensureValidAccessToken]，过期则用 refresh_token 自动刷新；
 *     refresh_token 也失效（30 天）则清空并提示重新授权。
 *
 * client_id 来自 BuildConfig（公开标识，反编译拿到也无法冒充本应用授权）；
 * **client_secret 不再使用**——PKCE 保证授权码即使被截获也无法换 token。
 */
object LxnsOAuthUtil {
    private const val TAG = "LxnsOAuthUtil"

    /** 无回调方案：授权后直接展示授权码，不重定向到任何地址。 */
    private const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    /** 申请的权限范围：读个人信息 + 读写玩家成绩。 */
    private const val SCOPE = "read_user_profile read_player write_player"

    private const val AUTHORIZE_URL = "https://maimai.lxns.net/oauth/authorize"
    private const val TOKEN_URL = "https://maimai.lxns.net/api/v0/oauth/token"

    /** access_token 提前刷新的缓冲（秒）：剩余 ≤2min 即强制刷新，避免临界过期拿到就过期。 */
    private const val REFRESH_BUFFER_SECONDS = 120L

    /** PKCE code_verifier 长度（RFC 7636 推荐 43-128 字符，这里取 64 字节 base64url 编码后约 86 字符）。 */
    private const val CODE_VERIFIER_BYTES = 64

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

    /**
     * 生成 PKCE code_verifier（高熵随机字符串）。
     * 使用 SecureRandom 生成 64 字节，base64url 编码（无 padding）。
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(CODE_VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /**
     * 从 code_verifier 计算 code_challenge (S256)。
     * challenge = base64url( SHA256( verifier ) )，无 padding。
     */
    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /**
     * 拼好带 PKCE 参数的授权链接。
     * 生成随机 code_verifier 存入 ConfigStorage，计算 code_challenge (S256)，
     * 授权链接带 code_challenge + code_challenge_method=S256。
     */
    fun getAuthorizeUrl(): String {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // 存 code_verifier，exchangeCodeForToken 时取出
        val cfg = application.configManager.config
        cfg.lxnsOAuthPkceVerifier = codeVerifier
        application.configManager.save()

        val scopeEncoded = SCOPE.replace(" ", "+")
        return "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${BuildConfig.LXNS_OAUTH_CLIENT_ID}" +
            "&redirect_uri=${REDIRECT_URI.replace(":", "%3A")}" +
            "&scope=$scopeEncoded" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256"
    }

    /**
     * 用授权码 + code_verifier 换 access_token / refresh_token（PKCE，无需 client_secret）。
     * 成功后存入 ConfigStorage，并清空 code_verifier（一次性使用）。
     */
    suspend fun exchangeCodeForToken(code: String): Boolean {
        if (code.isBlank()) {
            sendMessageToUi("授权码不能为空")
            return false
        }
        val cfg = application.configManager.config
        val codeVerifier = cfg.lxnsOAuthPkceVerifier
        if (codeVerifier.isBlank()) {
            sendMessageToUi("PKCE 验证码丢失，请重新授权")
            return false
        }
        val body = mapOf(
            "client_id" to BuildConfig.LXNS_OAUTH_CLIENT_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to codeVerifier
        )
        val ok = postTokenAndStore(body, hint = "授权码换令牌")
        if (ok) {
            // code_verifier 一次性使用，换 token 后立即清空
            cfg.lxnsOAuthPkceVerifier = ""
            application.configManager.save()
        }
        return ok
    }

    /**
     * 确保 access_token 仍有效：未过期直接返回；过期且有 refresh_token 则尝试刷新；
     * refresh_token 为空或刷新失败则返回 null，**不清空本地令牌、不主动提示重新授权**，
     * 避免给用户压力——只在 API 实际报错时由调用方提示重新绑定。
     */
    suspend fun ensureValidAccessToken(force: Boolean = false): String? {
        val cfg = application.configManager.config
        val now = System.currentTimeMillis()
        if (!force && cfg.lxnsOAuthAccessToken.isNotEmpty() &&
            cfg.lxnsOAuthAccessTokenExpireAt > now + REFRESH_BUFFER_SECONDS * 1000
        ) {
            return cfg.lxnsOAuthAccessToken
        }
        if (cfg.lxnsOAuthRefreshToken.isBlank()) {
            return null
        }
        // 刷新 token 也不需要 client_secret（PKCE 公共客户端）
        val body = mapOf(
            "client_id" to BuildConfig.LXNS_OAUTH_CLIENT_ID,
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
        cfg.lxnsOAuthPkceVerifier = ""
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
            val resp = io.github.teriver.maiupload.core.prober.client.post(TOKEN_URL) {
                contentType(ContentType.Application.Json)
                setBody(bodyToStringJson(body))
            }
            val respText = resp.bodyAsText()
            if (resp.status.value != 200) {
                val err = try { json.decodeFromString<TokenErrorResponse>(respText) } catch (_: Exception) { null }
                val msg = err?.let { "${it.error}${it.error_description?.let { d -> ": $d" } ?: ""}" } ?: respText
                DebugLog.log("E", TAG, "$hint 失败: $msg")
                sendMessageToUi("${hint}失败: $msg")
                false
            } else {
                val token = json.decodeFromString<TokenResponse>(respText)
                val cfg = application.configManager.config
                cfg.lxnsOAuthAccessToken = token.access_token
                cfg.lxnsOAuthRefreshToken = token.refresh_token
                cfg.lxnsOAuthAccessTokenExpireAt = System.currentTimeMillis() +
                    max(token.expires_in - REFRESH_BUFFER_SECONDS, 0L) * 1000
                application.configManager.save()
                DebugLog.log("D", TAG, "$hint 成功，access_token 有效期 ${token.expires_in}s")
                true
            }
        } catch (e: Exception) {
            DebugLog.log("E", TAG, "$hint 异常: ${e.message}", e)
            sendMessageToUi("${hint}异常: ${e.message}")
            false
        }
    }

    private fun bodyToStringJson(body: Map<String, String>): String =
        body.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
}
