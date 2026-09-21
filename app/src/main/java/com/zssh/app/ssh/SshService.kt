package com.zssh.app.ssh

import com.zssh.app.data.AuthType
import com.zssh.app.data.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/** 桌面端 detect 阶段的同等产物：平台/架构归一化（对齐 chunk-CZT7LLB3.js 的归一化表） */
data class DetectResult(
    val rawPlatform: String, val rawArch: String, val ostype: String, val home: String,
) {
    val platform: String get() = when {
        rawPlatform.equals("Linux", true) || ostype.contains("linux", true) -> "linux"
        rawPlatform.equals("Darwin", true) -> "darwin"
        rawPlatform.startsWith("MINGW", true) || rawPlatform.startsWith("MSYS", true) -> "win32"
        else -> rawPlatform.lowercase()
    }
    val arch: String get() = when (rawArch.lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64e" -> "arm64"
        else -> rawArch.lowercase()
    }
    /** CDN manifest 的 platformArch 字段值，如 linux-x64 / linux-arm64 */
    val manifestArch: String get() = "$platform-$arch"
}

sealed class ConnectEvent {
    data class Step(val text: String) : ConnectEvent()
    data class Failed(val message: String) : ConnectEvent()
}

/** sshj 封装：连接认证 + detect（对应桌面端 SSHBackend.buildSSHConnectConfig + detect） */
object SshService {

    init {
        // 安卓内置的精简版 BC 缺 X25519/Ed25519，会导致 "no such algorithm: X25519 for provider BC"。
        // 移除后注册 sshj 自带的完整版 BouncyCastle（sshj 在安卓上的标准做法）。
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun connect(config: ConnectionConfig): SSHClient {
        val ssh = SSHClient()
        ssh.useCompression()
        // 连接超时 15s：网络黑洞时快速失败，不再永远转圈
        ssh.connectTimeout = 15_000
        // M1: 先信任所有主机指纹；M4 换成首次记录+比对（对应桌面的 host key 流程）
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(config.host, config.port)
        // keepalive 30s：NAT 静默断连后尽快发现，而不是等下一次操作才报错
        runCatching { ssh.connection.keepAlive.keepAliveInterval = 30 }
        when (config.authType) {
            AuthType.PASSWORD -> {
                val pwd = (config.password ?: "")
                // 对齐桌面端 ssh-backend：password 与 keyboard-interactive 双方式并试。
                // 只用 authPassword 时，PAM/keyboard-interactive-only 的 sshd 会报
                // "Exhausted available authentication methods"（即使密码正确）。
                val finder = object : net.schmizz.sshj.userauth.password.PasswordFinder {
                    override fun reqPassword(replyToPrompt: net.schmizz.sshj.userauth.password.Resource<*>?) = pwd.toCharArray()
                    override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>?) = false
                }
                ssh.auth(
                    config.username,
                    net.schmizz.sshj.userauth.method.AuthPassword(finder),
                    net.schmizz.sshj.userauth.method.AuthKeyboardInteractive(
                        net.schmizz.sshj.userauth.method.PasswordResponseProvider(finder),
                    ),
                )
            }
            AuthType.PRIVATE_KEY -> {
                val pem = config.privateKeyPem ?: throw IllegalArgumentException("未导入私钥")
                val finder = config.passphrase?.let {
                    net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(it.toCharArray())
                }
                val keyProvider = ssh.loadKeys(pem, null, finder)
                ssh.authPublickey(config.username, keyProvider)
            }
        }
        return ssh
    }

    /** 按桌面端向导的步骤粒度产出事件，UI 逐条显示 */
    suspend fun detect(config: ConnectionConfig, onStep: (String) -> Unit): DetectResult =
        withContext(Dispatchers.IO) {
            var ssh: SSHClient? = null
            try {
                onStep("建立 SSH 连接 ${config.host}:${config.port} …")
                ssh = connect(config)
                onStep("认证通过（${if (config.authType == AuthType.PASSWORD) "密码" else "私钥"}）")

                val session = ssh.startSession()
                // sshj 一条 session 通道只能执行一条命令，每条命令必须新开会话并关闭
                fun exec(cmd: String): String {
                    val s = ssh.startSession()
                    return try {
                        val c = s.exec(cmd)
                        c.join(15, java.util.concurrent.TimeUnit.SECONDS)
                        c.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                    } finally {
                        s.close()
                    }
                }
                session.close()

                onStep("探测远端环境 …")
                val r = DetectResult(
                    rawPlatform = exec("uname -s"),
                    rawArch = exec("uname -m"),
                    ostype = exec("if [ -r /proc/sys/kernel/ostype ]; then cat /proc/sys/kernel/ostype; fi"),
                    home = exec("printf %s \"\$HOME\""),
                )
                onStep("检测完成：${r.manifestArch}，HOME=${r.home}")
                r
            } catch (e: UserAuthException) {
                android.util.Log.e(TAG, "detect 认证失败 host=${config.host}:${config.port} user=${config.username}", e)
                throw IllegalStateException("SSH 认证失败：请检查用户名、密码或私钥配置（${e.message?.take(80)}）", e)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "detect 连接失败 host=${config.host}:${config.port} user=${config.username}", e)
                val msg = if (e.message?.contains("timed out", true) == true ||
                    e.message?.contains("timeout", true) == true
                ) "SSH 连接握手超时（15s）：主机不可达或网络不通" else (e.message ?: "未知错误")
                throw IllegalStateException(msg, e)
            } finally {
                runCatching { ssh?.disconnect() }
            }
        }

    /** M1 附带验证：SFTP 读取远端文件大小（M2 的 SFTP 上传走同一通道） */
    suspend fun sftpStatSize(config: ConnectionConfig, remotePath: String): Long =
        withContext(Dispatchers.IO) {
            val ssh = connect(config)
            try {
                ssh.newSFTPClient().use { sftp ->
                    val attrs = sftp.statExistence(remotePath)
                        ?: throw IllegalStateException("远端文件不存在: $remotePath")
                    attrs.size
                }
            } finally {
                runCatching { ssh.disconnect() }
            }
        }
}

private const val TAG = "SshService"
