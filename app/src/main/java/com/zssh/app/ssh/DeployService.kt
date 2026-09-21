package com.zssh.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * M2-A 部署模块：远端自下载安装 zcode-server（对应桌面端 RemoteDownloadAssetInstaller 的精简版）。
 *
 * 流程：检查远端工具 → 发现版本并拉取 manifest-{platformArch}.json → 与
 * ~/.zcode/server/.asset-components/ 下各 JSON 的 SHA marker 比对（增量）→ 逐组件
 * 「下载 tar.gz → sha256 校验 → 解压 → 原子落位 → 写 marker」→ zcode-server --version 健康检查。
 * 手机只出脚本与清单的流量，组件全部由远端自行下载。
 *
 * 落位布局与桌面端 deploy.ts 完全一致（manifest 组件包内含发布目录结构）：
 *  node-runtime → node/{pa}/node              → ~/.zcode/server/node（可执行）
 *  server-bundle → server/zcode-server.cjs    → ~/.zcode/server/zcode-server.cjs
 *  node-pty → node-pty/{pa}/pty.node          → ~/.zcode/server/build/Release/pty.node
 *  glm → glm/{pa}/zcode.cjs + packages/       → ~/.zcode/server/agents/glm/（含 .version）
 *  bfs/ripgrep/ugrep → tools/{pa}/{名}/       → ~/.zcode/server/tools/{名}/
 *
 * 未移植官方的 .deploy.lock 远端互斥（APK 单用户单连接，UI 层用 deploying 标志防重入）。
 */
object DeployService {

    /** 必装组件（清单里缺失即失败）；node-pty 与搜索工具缺失只降级提示 */
    private val REQUIRED = listOf("node-runtime", "server-bundle", "glm")

    /** 安装顺序与官方 deploy.ts 一致：node → server → pty → glm → tools */
    private val ORDER = listOf("node-runtime", "server-bundle", "node-pty", "glm", "bfs", "ripgrep", "ugrep")

    data class Component(
        val id: String,
        val version: String,
        val sha256: String,
        val artifactPath: String,
    )

    data class Outcome(
        val appVersion: String,
        val installed: List<String>,
        val skipped: List<String>,
        val warnings: List<String>,
    )

    /** 单引号包裹的 shell 参数：空格与元字符都安全 */
    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    suspend fun deploy(
        ssh: SSHClient,
        detect: DetectResult,
        cdnBase: String,
        onStep: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val base = cdnBase.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "CDN 地址必须以 http(s):// 开头" }
        val pa = detect.manifestArch
        if (pa.startsWith("win32")) throw IllegalStateException("远端是 Windows：官方远程部署仅支持 POSIX（Linux/macOS）")

        onStep("检查远端下载工具（curl/wget、tar、sha256）…")
        val tools = exec(
            ssh,
            "command -v curl >/dev/null 2>&1 && echo CURL; command -v wget >/dev/null 2>&1 && echo WGET; " +
                "command -v tar >/dev/null 2>&1 && echo TAR; " +
                "{ command -v sha256sum >/dev/null 2>&1 && echo SHA256SUM; } || " +
                "{ command -v shasum >/dev/null 2>&1 && echo SHASUM; }",
            30,
        )
        if (!tools.contains("TAR")) throw IllegalStateException("远端缺少 tar，无法部署")
        if (!tools.contains("CURL") && !tools.contains("WGET"))
            throw IllegalStateException("远端没有 curl 也没有 wget，无法自行下载")
        if (!tools.contains("SHA256SUM") && !tools.contains("SHASUM"))
            throw IllegalStateException("远端缺少 sha256sum / shasum，无法校验组件完整性")

        onStep("获取组件清单（$pa）…")
        val (appVersion, components) = fetchManifest(ssh, base, pa)
        val byId = components.associateBy { it.id }
        val missing = REQUIRED.filter { byId[it] == null }
        if (missing.isNotEmpty()) throw IllegalStateException("CDN 清单缺少必装组件：${missing.joinToString()}")

        onStep("比对已部署组件（按 SHA 增量）…")
        val markers = readMarkers(ssh)
        val toInstall = ORDER.filter { byId.containsKey(it) }
            .filter { id -> markers[id]?.optString("sha256") != byId[id]!!.sha256 || markers[id]?.optString("platformArch") != pa }
        val skipped = ORDER.filter { byId.containsKey(it) && it !in toInstall }
        if (toInstall.isEmpty()) onStep("全部组件已是最新，无需下载")

        val installed = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (id in toInstall) {
            val c = byId[id]!!
            onStep("下载并安装 $id @ ${c.version}（sha ${c.sha256.take(8)}…，大组件可能需要几分钟）")
            val output = exec(ssh, installScript(base, appVersion, pa, c), 900)
            if ("BUILTIN_COPIED" in output) warnings += "已把 glm 组件内的 provider/zcode-builtin.json 物化到 ~/.zcode/v2"
            installed += id
        }
        if (byId.containsKey("node-pty") && "node-pty" !in toInstall && "node-pty" !in markers)
            warnings += "node-pty 未安装（终端能力不可用）"

        onStep("健康检查（zcode-server --version）…")
        val v = exec(ssh, "~/.zcode/server/node ~/.zcode/server/zcode-server.cjs --version 2>&1", 60).trim()
        if (v.isBlank()) throw IllegalStateException("部署后 zcode-server --version 无输出，请查看远端 ~/.zcode/server 布局")
        if (v != appVersion) warnings += "远端版本 $v 与清单版本 $appVersion 不一致"
        onStep("部署完成：v$v")

        Outcome(appVersion = v, installed = installed, skipped = skipped, warnings = warnings)
    }

    /** 版本发现走 CLI 安装器约定 {base}/latest.json；清单优先带版本路径，回退根路径 */
    private suspend fun fetchManifest(ssh: SSHClient, base: String, pa: String): Pair<String, List<Component>> {
        var version = ""
        val latest = fetchText(ssh, "$base/latest.json")
        if (latest.isNotBlank()) {
            version = runCatching { JSONObject(latest).optString("version") }.getOrDefault("").trim()
        }
        val candidates = buildList {
            if (version.isNotBlank()) add("$base/$version/manifest-$pa.json")
            add("$base/manifest-$pa.json")
        }.distinct()
        for (url in candidates) {
            val text = fetchText(ssh, url)
            if (text.isBlank() || !text.startsWith("{")) continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val comps = root.optJSONArray("components") ?: continue
            val list = (0 until comps.length()).mapNotNull { i ->
                val o = comps.optJSONObject(i) ?: return@mapNotNull null
                Component(
                    id = o.optString("id"),
                    version = o.optString("version"),
                    sha256 = o.optString("sha256").lowercase(),
                    artifactPath = o.optString("artifactPath"),
                )
            }.filter { it.id.isNotBlank() && it.sha256.length == 64 && it.artifactPath.isNotBlank() }
            if (list.isEmpty()) continue
            val appVersion = root.optString("appVersion").ifBlank { version }
            if (appVersion.isBlank()) throw IllegalStateException("清单缺少 appVersion：$url")
            val manifestPa = root.optString("platformArch")
            if (manifestPa.isNotBlank() && manifestPa != pa)
                throw IllegalStateException("清单平台 $manifestPa 与远端 $pa 不匹配（CDN 地址或版本可能不对）")
            return appVersion to list
        }
        throw IllegalStateException("拿不到组件清单：${candidates.joinToString("、")}（检查 CDN 地址与网络）")
    }

    /** 远端取文本；失败（404/超时/非 0 退出）一律按"未找到"返回空串 */
    private suspend fun fetchText(ssh: SSHClient, url: String): String = try {
        exec(
            ssh,
            "if command -v curl >/dev/null 2>&1; then curl -fsSL --connect-timeout 15 ${shq(url)}; " +
                "else wget -qO- -T 30 ${shq(url)}; fi",
            60,
        ).trim()
    } catch (e: Exception) {
        ""
    }

    /** 读远端 SHA marker：.asset-components/ 下每个 JSON 文件一行 */
    private suspend fun readMarkers(ssh: SSHClient): Map<String, JSONObject> {
        val out = exec(
            ssh,
            "cd \"\$HOME/.zcode/server/.asset-components\" 2>/dev/null && for f in *.json; do " +
                "[ -f \"\$f\" ] && cat \"\$f\" && echo; done; true",
            30,
        )
        return out.lineSequence()
            .mapNotNull { line -> runCatching { JSONObject(line.trim()) }.getOrNull() }
            .filter { it.optString("id").isNotBlank() }
            .associateBy { it.optString("id") }
    }

    private fun installScript(base: String, appVersion: String, pa: String, c: Component): String {
        val urls = listOf("$base/$appVersion/${c.artifactPath}", "$base/${c.artifactPath}").distinct()
        val marker = JSONObject()
            .put("id", c.id).put("version", c.version)
            .put("sha256", c.sha256).put("platformArch", pa).toString()
        return listOf(
            "set -eu",
            "ROOT=\"\$HOME/.zcode/server\"",
            "mkdir -p \"\$ROOT\"",
            "STAGE=\$(mktemp -d \"\$ROOT/.deploy-stage.XXXXXX\")",
            "cleanup() { rm -rf \"\$STAGE\"; }",
            "trap cleanup EXIT",
            "fetch() { if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 --connect-timeout 20 -o \"\$2\" \"\$1\"; " +
                "else wget -q --tries=3 -T 60 -O \"\$2\" \"\$1\"; fi; }",
            "GOT=0",
            urls.joinToString("\n") { u -> "if fetch ${shq(u)} \"\$STAGE/pkg.tar.gz\"; then GOT=1; fi" },
            "[ \"\$GOT\" = 1 ] || { echo 'DOWNLOAD_FAILED ${c.id}'; exit 1; }",
            "if command -v sha256sum >/dev/null 2>&1; then CHK=sha256sum; else CHK='shasum -a 256'; fi",
            "printf '%s  %s\\n' ${shq(c.sha256)} \"\$STAGE/pkg.tar.gz\" | \$CHK -c - >/dev/null 2>&1 " +
                "|| { echo 'SHA_MISMATCH ${c.id}'; exit 1; }",
            "mkdir -p \"\$STAGE/x\"",
            "tar -xzf \"\$STAGE/pkg.tar.gz\" -C \"\$STAGE/x\"",
            placementCommands(c.id, pa, c.version),
            "mkdir -p \"\$ROOT/.asset-components\"",
            "printf '%s' ${shq(marker)} > \"\$ROOT/.asset-components/${c.id}.json\"",
            "echo 'OK ${c.id}'",
        ).joinToString("\n")
    }

    /** 组件包内路径 → ~/.zcode/server 下的最终位置（与官方 installFile/installDirectory 等价） */
    private fun placementCommands(id: String, pa: String, version: String): String {
        val x = "\"\$STAGE/x\""
        val r = "\"\$ROOT\""
        return when (id) {
            "node-runtime" ->
                "cp $x/node/$pa/node $r/node.new && chmod 755 $r/node.new && mv -f $r/node.new $r/node"
            "server-bundle" ->
                "cp $x/server/zcode-server.cjs $r/zcode-server.cjs.new && mv -f $r/zcode-server.cjs.new $r/zcode-server.cjs"
            "node-pty" ->
                "mkdir -p $r/build/Release\n" +
                    "cp $x/node-pty/$pa/pty.node $r/build/Release/pty.node.new && mv -f $r/build/Release/pty.node.new $r/build/Release/pty.node\n" +
                    "if [ -f $x/node-pty/$pa/spawn-helper ]; then cp $x/node-pty/$pa/spawn-helper $r/build/Release/spawn-helper.new && mv -f $r/build/Release/spawn-helper.new $r/build/Release/spawn-helper; fi"
            "glm" ->
                "rm -rf $r/agents/glm.new\n" +
                    "mkdir -p $r/agents/glm.new\n" +
                    "cp -R $x/glm/$pa/. $r/agents/glm.new/\n" +
                    "printf '%s' ${shq(version)} > $r/agents/glm.new/.version\n" +
                    "rm -rf $r/agents/glm\n" +
                    "mv -f $r/agents/glm.new $r/agents/glm\n" +
                    // 引擎的 builtin 供应商配置：glm 组件若带 provider/zcode-builtin.json 且目标缺失则物化一份
                    //（AgentSession 启动命令显式指向该路径）
                    "if [ -f $r/agents/glm/provider/zcode-builtin.json ] && [ ! -f \"\$HOME/.zcode/v2/runtime/provider/bundled/zcode-builtin.json\" ]; then " +
                    "mkdir -p \"\$HOME/.zcode/v2/runtime/provider/bundled\" && " +
                    "cp $r/agents/glm/provider/zcode-builtin.json \"\$HOME/.zcode/v2/runtime/provider/bundled/zcode-builtin.json\" && echo 'BUILTIN_COPIED'; fi"
            // bfs / ripgrep / ugrep：整目录落位（包内 tools/{pa}/{名}/ → tools/{名}/）
            else ->
                "rm -rf $r/tools/$id.new\n" +
                    "mkdir -p $r/tools/$id.new\n" +
                    "cp -R $x/tools/$pa/$id/. $r/tools/$id.new/\n" +
                    "printf '%s' ${shq(version)} > $r/tools/$id.new/.version\n" +
                    "rm -rf $r/tools/$id\n" +
                    "mv -f $r/tools/$id.new $r/tools/$id\n" +
                    "chmod 755 $r/tools/$id/${binaryName(id)} 2>/dev/null || true"
        }
    }

    private fun binaryName(id: String) = when (id) {
        "ripgrep" -> "rg"
        "ugrep" -> "ugrep"
        else -> id
    }

    /** exec 并校验 join 超时与退出码（超时/失败都抛异常，stderr 附进错误信息） */
    private suspend fun exec(ssh: SSHClient, cmd: String, timeoutSec: Long): String = withContext(Dispatchers.IO) {
        val s = ssh.startSession()
        try {
            val c = s.exec(cmd)
            try {
                c.join(timeoutSec, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException("远端命令超时或连接中断（>${timeoutSec}s）：${cmd.take(80)}…", e)
            }
            val out = c.inputStream.readBytes().toString(Charsets.UTF_8)
            if (c.exitStatus != 0) {
                val err = runCatching { c.errorStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                val tail = err.trim().take(300).ifBlank { out.trim().take(300) }
                throw IllegalStateException("远端命令失败（exit=${c.exitStatus}）${if (tail.isBlank()) "" else "：$tail"}")
            }
            out
        } finally {
            runCatching { s.close() }
        }
    }
}
