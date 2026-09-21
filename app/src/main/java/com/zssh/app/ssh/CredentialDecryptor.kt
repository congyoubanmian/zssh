package com.zssh.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * C6：离线解密远端 ~/.zcode/v2/credentials.json，为额度页补 OAuth 鉴权分支。
 *
 * 算法与官方 credential-cipher.ts 逐字段一致：
 *  - 密钥：sha256("zcode-credential-fallback:{platform}:{homeDir}:{username}")，32 字节 AES key。
 *    platform=DetectResult.platform（linux/darwin）、homeDir=远端 $HOME（detect.home）、
 *    username=SSH 登录名（Node 端 os.userInfo().username 取 passwd 登录名，与之一致；
 *    远端经 su 切换等场景不一致时密钥自然不匹配 → 解密失败降级）。
 *    远端若设置了 ZCODE_CREDENTIAL_SECRET，密钥不是该公式推导的 → 必然解密失败。
 *  - 值格式："enc:v1:" 前缀 + base64url(iv).base64url(authTag).base64url(cipher)，
 *    AES-256-GCM（iv 12 字节、authTag 16 字节），JVM Cipher 即可。
 *
 * 降级约定：任何失败（未连接 / 文件不存在 / 非 JSON / 密钥不匹配 / 格式不符）都返回 null，
 * 调用方据此走仅 API Key 分支，额度页整体不能因解密报错。
 *
 * 凭据键名与官方 shared-credentials.ts 的 SHARED_ZCODE_CREDENTIAL_KEYS 一致；只解额度查询
 * 用得到的键（refresh token 官方链路未实现、user_info 额度页用不上，均不解以缩小明文面）。
 *
 * 接入方式（C1 QuotaService 的 Key 来源逻辑预留分支，示例）：
 * ```
 * // Key 来源优先级：
 * //  1. 本地套餐供应商 API Key —— Authorization 头直传 API Key 原文，禁止 Bearer 前缀
 * //     （bigmodel monitor 接口契约，见 PORTING-PLAN 批次二注意事项）
 * //  2. [C6 预留] 远端 OAuth token —— 仅业务订阅查询路径（{业务域}/api/biz/subscription/list 族）：
 * //     val creds = CredentialDecryptor.loadRemote()   // 失败返回 null，不抛异常
 * //     val authorization = creds?.authorizationForFamily("zai")   // "Bearer <access_token>"
 * //     zai 家族套 Bearer 前缀、bigmodel 家族直传 token 原文（与官方
 * //     bigmodelSubscriptionProvider.ts 的 family 分支一致）；quota/limit 本体仍走第 1 来源。
 * //  3. 两者都无：额度页显示"未配置"，不报错。
 * ```
 */
object CredentialDecryptor {

    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val FALLBACK_SECRET_PREFIX = "zcode-credential-fallback:"
    private const val IV_BYTES = 12
    private const val AUTH_TAG_BYTES = 16
    private const val GCM_TAG_BITS = 128

    private const val KEY_ACTIVE_PROVIDER = "oauth:active_provider"
    private const val KEY_ZAI_ACCESS = "oauth:zai:access_token"
    private const val KEY_BIGMODEL_ACCESS = "oauth:bigmodel:access_token"
    private const val KEY_ZCODE_JWT = "zcodejwttoken"

    /** 解密后的远端 OAuth 凭据；字段缺失或该键解密失败时为 null */
    data class RemoteCredentials(
        val activeProvider: String?,      // "zai" | "bigmodel"
        val zaiAccessToken: String?,
        val bigmodelAccessToken: String?,
        val zcodeJwtToken: String?,
    ) {
        /** 业务订阅查询（zai/bigmodel 家族对称路径）的 Authorization 头；无 token 返回 null。
         *  zai 家族套 Bearer 前缀、bigmodel 家族直传原文——与官方实现逐分支一致。 */
        fun authorizationForFamily(family: String): String? = when (family) {
            "zai" -> zaiAccessToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            "bigmodel" -> bigmodelAccessToken?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** 拉取并解密远端凭据；任何一步失败返回 null（调用方降级为仅 API Key 分支，不抛异常） */
    suspend fun loadRemote(): RemoteCredentials? = withContext(Dispatchers.IO) {
        val detect = AppSession.detect ?: return@withContext null
        val config = AppSession.config ?: return@withContext null
        // 与 pullProviderConfig 同款读法：文件不存在时 cat 无输出
        val content = try {
            AppSession.execRemote("cat \$HOME/.zcode/v2/credentials.json 2>/dev/null")
        } catch (e: Exception) {
            return@withContext null
        }
        if (content.isBlank()) return@withContext null
        val root = try {
            JSONObject(content)
        } catch (e: Exception) {
            return@withContext null
        }
        val key = deriveKey(detect.platform, AppSession.homeDir, config.username)
        val credentials = RemoteCredentials(
            activeProvider = decryptField(root, KEY_ACTIVE_PROVIDER, key),
            zaiAccessToken = decryptField(root, KEY_ZAI_ACCESS, key),
            bigmodelAccessToken = decryptField(root, KEY_BIGMODEL_ACCESS, key),
            zcodeJwtToken = decryptField(root, KEY_ZCODE_JWT, key),
        )
        // 一个凭据都没解出来（典型：远端设了 ZCODE_CREDENTIAL_SECRET 导致密钥不匹配）→ 整体按失败降级
        if (credentials.zaiAccessToken == null &&
            credentials.bigmodelAccessToken == null &&
            credentials.zcodeJwtToken == null
        ) null else credentials
    }

    /** 密钥推导：sha256("zcode-credential-fallback:{platform}:{homeDir}:{username}") */
    fun deriveKey(platform: String, homeDir: String, username: String): SecretKeySpec {
        val secret = FALLBACK_SECRET_PREFIX + platform + ":" + homeDir + ":" + username
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /** 解密单个字段：键缺失 / 解密失败返回 null；无 enc 前缀时按官方语义原样返回（宽容旧明文） */
    fun decryptField(root: JSONObject, name: String, key: SecretKeySpec): String? {
        val raw = root.optString(name)
        if (raw.isBlank()) return null
        return decrypt(raw, key)?.takeIf { it.isNotBlank() }
    }

    /** 与 credential-cipher.ts 的 decrypt 等价；格式不符或密钥不匹配返回 null 而非抛异常 */
    fun decrypt(value: String, key: SecretKeySpec): String? {
        if (!value.startsWith(ENCRYPTED_PREFIX)) return value
        val parts = value.removePrefix(ENCRYPTED_PREFIX).split(".")
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        val decoder = Base64.getUrlDecoder()
        val iv = runCatching { decoder.decode(parts[0]) }.getOrNull() ?: return null
        val authTag = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        val cipherText = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return null
        if (iv.size != IV_BYTES || authTag.size != AUTH_TAG_BYTES || cipherText.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            // JCE 的 GCM 要求 authTag 拼在密文末尾（官方 Node 侧是分离 setAuthTag，语义相同）
            String(cipher.doFinal(cipherText + authTag), Charsets.UTF_8)
        } catch (e: Exception) {
            // AEADBadTagException：密钥不匹配（远端设了 ZCODE_CREDENTIAL_SECRET）或密文损坏
            null
        }
    }
}
