package io.github.lnrplugin.wenkukeeper.migrate

import android.os.Build
import java.lang.reflect.Method

/**
 * 简繁字形归一。
 *
 * 迁移的来源与目标常常分属简体站与繁体站（例如 `wenku8.net` → `tw.linovelib.com`），
 * 同一本书的书名会是「魔法剑士」与「魔法劍士」。比对前统一字形，否则逐字相似度会把
 * 本该高置信的匹配压到低置信。
 *
 * ## 为什么用反射而不是直接 `import android.icu.text.Transliterator`
 *
 * 宿主的 `PluginClassLoader` 是**子加载器优先**：除了白名单（`kotlin.`、`kotlinx.`、
 * `androidx.`、`j$.`、`io.nightfish.lightnovelreader.api.`）之外，它先 `findClass`
 * 再委托父加载器，而 `android.` 不在白名单里。
 *
 * AGP 又必然会在 APK 里塞进一个「全局合成类」dex，其中 824 个条目是 `android.jar` 的
 * **空壳定义**（每个类只声明 `<clinit>`，没有任何真实成员），且无法关闭——
 * `android.enableGlobalSyntheticsGeneration` 是废弃占位项，AGP 9.2.1 会直接报
 * “removed in version 8.1 of the Android Gradle plugin” 并中断构建。
 *
 * 两者相叠，插件里 `import android.icu.text.Transliterator` 就会解析到那个空壳，
 * `getInstance` 自然不存在，抛出的 `NoSuchMethodError` 会被 [create] 里的 `runCatching`
 * 吃掉，于是 [supported] 恒为 false、简繁归一**静默失效**——不会崩，但跨简繁匹配率
 * 悄悄退化，而跨简繁正是这个功能存在的理由。
 *
 * 所以这里改成用**宿主（系统）类加载器**取类：`PathClassLoader` 不覆写 `loadClass`，
 * 是父加载器优先，`android.icu.*` 一定由 boot classpath 提供，拿到的是真实现。
 *
 * ## 关于版本门槛
 *
 * `android.icu.text.Transliterator` 类自 API 24 就存在，但 `Simplified-Traditional`
 * 转换器依赖设备上的 ICU 转换数据，只有 API 29+ 才稳定可用。因此以 **Q(29)** 为界：
 * - API 29 及以上：简繁双向均可用；
 * - API 29 以下：[normalize] 原样返回，匹配退化为按原字比对（不会崩，只是匹配率下降）。
 *
 * `minSdk` 是 24，所以低版本设备确实会走到退化分支。这是有意的取舍：要让低版本也能
 * 转换，得内置整张简繁对照表（数十 KB，且正确性要自行保证），而这类设备占比很低。
 */
object ChineseVariant {

    /**
     * ICU 转换器类名。
     *
     * 故意写成字符串而不是 `import`：见类文档，直接 `import` 会绑定到插件自带空壳。
     */
    private const val TRANSLITERATOR_CLASS = "android.icu.text.Transliterator"

    /**
     * 反射句柄的封装。
     *
     * ICU 的 `Transliterator` 不是线程安全的，迁移会在 IO 线程并发取详情，因此调用点
     * 仍需加锁（见 [convert]）。
     */
    private class Converter(
        private val instance: Any,
        private val transliterate: Method
    ) {
        fun convert(text: String): String =
            transliterate.invoke(instance, text) as? String ?: text
    }

    /** 简 → 繁。 */
    private val simplifiedToTraditional: Converter? by lazy {
        create("Simplified-Traditional")
    }

    /** 繁 → 简。 */
    private val traditionalToSimplified: Converter? by lazy {
        create("Traditional-Simplified")
    }

    /** 当前设备是否支持字形转换。 */
    val supported: Boolean get() = simplifiedToTraditional != null

    /**
     * 把文本归一为**繁体**。
     *
     * 选繁体作为归一目标是因为迁移的目标站更常见的是繁体站（例如 linovelib），
     * 而繁体→简体再到繁体的往返在这些站点上更稳定。
     *
     * @param text 原始文本
     *
     * @return 归一后的文本；设备不支持转换时原样返回
     */
    fun normalize(text: String): String = convert(simplifiedToTraditional, text)

    /**
     * 把繁体文本转成简体，用于按简体关键词搜索繁体站时的回退尝试。
     *
     * @param text 原始文本
     *
     * @return 简体文本；设备不支持转换时原样返回
     */
    fun toSimplified(text: String): String = convert(traditionalToSimplified, text)

    /**
     * 套用转换器，任何失败都退化为原文。
     *
     * @param converter 转换器；为 null 表示设备不支持
     * @param text 原始文本
     */
    private fun convert(converter: Converter?, text: String): String {
        if (text.isEmpty() || converter == null) return text
        return runCatching {
            synchronized(converter) { converter.convert(text) }
        }.getOrDefault(text)
    }

    /**
     * 通过宿主的类加载器创建转换器。
     *
     * 不用 `Transliterator.getInstance(id)` 直连，是因为那会编译成对插件自有空壳的引用；
     * 这里用 `Class.forName(..., loader)` 让宿主（父加载器优先）去解析真正的框架类。
     *
     * 两道自检保证拿到的确实是平台实现而不是空壳：
     * 1. 得到的类必须**不是**插件自己的类加载器加载的；
     * 2. 必须真的存在 `getInstance(String)` 与 `transliterate(String)`。
     *
     * @param id ICU 转换器 id
     *
     * @return 转换器；设备不支持或解析到空壳时返回 null
     */
    private fun create(id: String): Converter? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val ownLoader = ChineseVariant::class.java.classLoader
            val clazz = Class.forName(TRANSLITERATOR_CLASS, true, ClassLoader.getSystemClassLoader())
            // 真实现由 boot classpath 提供，其 classLoader 为 null；若等于插件加载器说明命中了空壳。
            if (clazz.classLoader != null && clazz.classLoader === ownLoader) return@runCatching null
            val instance = clazz.getMethod("getInstance", String::class.java).invoke(null, id)
                ?: return@runCatching null
            Converter(instance, clazz.getMethod("transliterate", String::class.java))
        }.getOrNull()
    }
}
