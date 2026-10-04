package io.github.lnrplugin.wenkukeeper.update

import io.github.lnrplugin.wenkukeeper.PluginConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次更新检查的结果。
 */
sealed interface UpdateCheckResult {

    /** 远端有新版本。 */
    data class Available(val tag: String, val name: String, val url: String) : UpdateCheckResult

    /** 已经是最新。 */
    data class UpToDate(val tag: String) : UpdateCheckResult

    /** 仓库里还没有任何 Release —— 这不是错误，但它与"连不上"要分开说明。 */
    object NoRelease : UpdateCheckResult

    /** 检查本身失败（网络不通、限流、返回体读不懂等）。 */
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * GitHub Release 里我们需要的字段。
 *
 * 只声明用得到的几个：`ignoreUnknownKeys` 打开后，GitHub 增删字段都不会影响解析。
 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

/**
 * 插件自身的更新检查。
 *
 * ## 为什么不走宿主的更新检查
 *
 * 宿主确实有 `PluginUpdateCheckRepository`，但它查的是**官方插件商店**
 * （`https://plugins.nariko.org/api/plugins?id=<包名>`），而插件 API 里的
 * `@Plugin(updateUrl = ...)` 字段**宿主从未读取**（只在 `PluginMetadata` 里被搬运一次）。
 * 也就是说：没上架官方商店的插件，宿主永远不会提示更新。这里直接读插件自己的
 * GitHub Releases，不依赖商店。
 *
 * ## 边界
 *
 * - 只读一个公开的 `/releases/latest`，**不带任何凭据**；未登录时限流为每 IP 每小时 60 次，
 *   手动 + 每次打开插件页面最多一次足够用。
 * - 比较的是 **tag 与 [PluginConstants.PLUGIN_VERSION_NAME]**，因此发版时 tag 要写成
 *   `v1.1.0` 这种带 `v` 前缀的语义化版本（[compareVersions] 会忽略前缀）。
 * - 草稿与预发布版本不参与判断——`/releases/latest` 本身就会跳过它们。
 *
 * @param repository 发布仓库，`owner/repo` 形式
 * @param currentVersion 当前插件版本（与 `@Plugin(versionName)` 同源）
 */
class UpdateChecker(
    private val repository: String = PluginConstants.RELEASE_REPOSITORY,
    private val currentVersion: String = PluginConstants.PLUGIN_VERSION_NAME
) {

    companion object {
        private const val API_BASE = "https://api.github.com"

        /** 移动网络下 15 秒足够；超时后界面会给出可重试的提示，不会一直转圈。 */
        private const val TIMEOUT_MILLIS = 15_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 查询最新 Release 并与当前版本比较。
     *
     * 不会抛出异常（协程取消除外）：所有失败都表达为 [UpdateCheckResult.Failed]，
     * 由界面决定怎么显示。
     *
     * @return 检查结果
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        // GitHub 要求请求带 User-Agent，缺了会返回 403；用插件自己的名字而不是伪装浏览器。
        val client = HttpClient(CIO) {
            install(UserAgent) { agent = "WenkuKeeper/${PluginConstants.PLUGIN_VERSION_NAME}" }
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MILLIS
                connectTimeoutMillis = TIMEOUT_MILLIS
                socketTimeoutMillis = TIMEOUT_MILLIS
            }
        }
        try {
            val response = client.get("$API_BASE/repos/$repository/releases/latest") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            when {
                // 一个 Release 都没发过时 GitHub 返回 404。这与"网络失败"是两回事，
                // 必须分开：否则刚建好仓库的用户会以为插件坏了。
                response.status.value == 404 -> UpdateCheckResult.NoRelease
                !response.status.isSuccess() ->
                    UpdateCheckResult.Failed("GitHub 返回 HTTP ${response.status.value}")
                else -> evaluate(json.decodeFromString<GitHubRelease>(response.bodyAsText()))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            UpdateCheckResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
            )
        } finally {
            client.close()
        }
    }

    /**
     * 判断远端版本是否比本机新。
     *
     * @param release 解析后的 Release
     *
     * @return 有新版返回 [UpdateCheckResult.Available]，否则 [UpdateCheckResult.UpToDate]
     */
    private fun evaluate(release: GitHubRelease): UpdateCheckResult {
        if (release.tagName.isBlank()) {
            return UpdateCheckResult.Failed("仓库返回的 Release 没有 tag")
        }
        return if (compareVersions(release.tagName, currentVersion) > 0) {
            UpdateCheckResult.Available(
                tag = release.tagName,
                name = release.name.ifBlank { release.tagName },
                url = release.htmlUrl.ifBlank { "https://github.com/$repository/releases" }
            )
        } else {
            UpdateCheckResult.UpToDate(release.tagName)
        }
    }
}

/**
 * 比较两个版本号。
 *
 * 规则刻意宽松，因为 tag 是人写的：忽略 `v`/`V` 前缀，按 `.`、`-`、`+`、`_` 切段，
 * 每段只取开头的数字（`1.1.0-beta2` 里的 `beta2` 记作 0）。段数不同时短的一方补 0，
 * 因此 `1.1` 与 `1.1.0` 相等。
 *
 * @param a 左侧版本（通常是远端 tag）
 * @param b 右侧版本（通常是本机版本）
 *
 * @return a 大于 b 返回正数，相等返回 0，小于返回负数
 */
internal fun compareVersions(a: String, b: String): Int {
    fun segments(value: String): List<Int> = value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+', '_')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    val left = segments(a)
    val right = segments(b)
    for (index in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
        if (diff != 0) return if (diff > 0) 1 else -1
    }
    return 0
}
