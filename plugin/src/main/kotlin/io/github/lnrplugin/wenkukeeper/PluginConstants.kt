package io.github.lnrplugin.wenkukeeper

import io.nightfish.lightnovelreader.api.identifier.Identifier

/**
 * Build-wide constants shared by the data source, the UI and the cloud sync.
 *
 * Everything that has to stay consistent across those three concerns lives here, so there is
 * exactly one definition of each identifier.
 */
object PluginConstants {

    /** Value declared in [io.nightfish.lightnovelreader.api.plugin.Plugin.apiVersion]. */
    const val API_VERSION = 4

    /** Namespace part of every [Identifier] created by this plugin. */
    const val ID_NAMESPACE = "wenkukeeper"

    /**
     * Version of the plugin itself, shown by the host's plugin management screen.
     *
     * 它同时是更新检查的比较基准（远端 tag 大于它才提示新版本），因此**每次发版都要改**，
     * 并在仓库里打上对应的 `v<版本>` tag。
     */
    const val PLUGIN_VERSION_NAME = "1.1.0"

    /**
     * 插件的发布仓库（`owner/repo`）。
     *
     * 更新检查读它的 `/releases/latest`。之所以插件自己查，是因为宿主的更新检查只认官方
     * 插件商店（`plugins.nariko.org`），而 API 里的 `@Plugin(updateUrl)` 宿主从未读取。
     */
    const val RELEASE_REPOSITORY = "Einstein-Newton-666/WenkuKeeper"

    /** 项目主页；与 [RELEASE_REPOSITORY] 同源，避免两处写出不同地址。 */
    const val PROJECT_URL = "https://github.com/$RELEASE_REPOSITORY"

    /**
     * Identifier of the [io.nightfish.lightnovelreader.api.web.WebBookDataSource] contributed by
     * this plugin.
     *
     * This deliberately differs from the built-in Wenku8 source shipped by the host (whose id is
     * `Wenku8`): book ids are namespaced by data source id, so registering a second source under
     * the same id would make bookshelf entries ambiguous. Using our own namespace also lets the
     * user enable this source and the built-in one independently.
     */
    val WEB_DATA_SOURCE: Identifier = Identifier(ID_NAMESPACE, "wenku8")

    /** Display name of the contributed data source. */
    const val SOURCE_NAME = "文库管家"

    /** English/identifier name of the plugin, used in docs and repository names. */
    const val PLUGIN_NAME_EN = "WenkuKeeper"

    /** Provider string of the contributed data source. */
    const val SOURCE_PROVIDER = "wenku8.net · LightNovelReader 插件"

    /**
     * Mirrors of wenku8, tried in order. The first one that answers a health check becomes the
     * active host. `wenku8.net` is preferred because it is the canonical domain.
     */
    val HOSTS: List<String> = listOf(
        "https://www.wenku8.net",
        "https://www.wenku8.cc",
        "https://www.wenku8.com"
    )

    /**
     * wenku8 serves GB18030, but the response header sometimes claims GBK. Decoding must not rely
     * on the header, because characters such as `•`/`〜` are outside GBK and are transmitted using
     * GB18030-only four byte sequences. Reading raw bytes and decoding with this charset is the
     * only reliable option.
     */
    const val WENKU8_CHARSET = "GB18030"

    /**
     * The site rejects searches issued less than five seconds apart. The search provider waits at
     * least this long between consecutive search requests.
     */
    const val SEARCH_INTERVAL_MILLIS = 5_000L

    /** Concurrent request budget for the shared Ktor client, matching the built-in source. */
    const val MAX_CONCURRENT_REQUESTS = 4

    /**
     * Lifetime of the data source cache, in minutes.
     *
     * Matches the host's built-in wenku8 source. This is intentionally not a user setting:
     * the host reads `WebBookDataSource.cache` immediately after constructing the source,
     * on the main thread, so it cannot be populated from user data without either blocking
     * the UI thread or racing the read.
     */
    const val CACHE_TIMEOUT_MINUTES = 120

    /** Obsolete-but-still-linked alternate host for cover images. */
    const val IMAGE_HOST = "https://img.wenku8.com"

    /**
     * Tag list offered by wenku8. Used to decide whether a book tag can open the host's expanded
     * explore page.
     */
    val TAGS: List<String> = listOf(
        "校园", "青春", "恋爱", "治愈", "群像",
        "竞技", "音乐", "美食", "旅行", "欢乐向",
        "经营", "职场", "斗智", "脑洞", "宅文化",
        "穿越", "奇幻", "魔法", "异能", "战斗",
        "科幻", "机战", "战争", "冒险", "龙傲天",
        "悬疑", "犯罪", "复仇", "黑暗", "猎奇",
        "惊悚", "间谍", "末日", "游戏", "大逃杀",
        "青梅竹马", "妹妹", "女儿", "JK", "JC",
        "大小姐", "性转", "伪娘", "人外",
        "后宫", "百合", "耽美", "NTR", "女性视角"
    )
}
