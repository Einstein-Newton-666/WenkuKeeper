package io.github.lnrplugin.wenkukeeper.tools

import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * 安全的 XPath 选择工具。
 *
 * jsoup 的 XPath 支持由 jaxen 提供，选择器写错时可能返回空集合。这里统一返回可空结果，
 * 让调用方用 `?:` 回退到默认值或构造明确的错误，避免解析失败直接抛异常。
 */

/**
 * 按 XPath 取第一个匹配元素。
 *
 * @param xpath XPath 表达式
 *
 * @return 第一个匹配元素，没有匹配时返回 null
 */
fun Element.selectSingleXPath(xpath: String): Element? =
    runCatching { selectXpath(xpath).firstOrNull() }.getOrNull()

/**
 * 按 XPath 取全部匹配元素。
 *
 * @param xpath XPath 表达式
 *
 * @return 匹配到的元素集合，表达式非法时返回空集合
 */
fun Element.selectXPathOrEmpty(xpath: String): Elements =
    runCatching { selectXpath(xpath) }.getOrElse { Elements() }

/**
 * 取元素的文本并去除多余空白。
 *
 * @return 规范化后的文本，空节点返回空字符串
 */
fun Element.cleanText(): String = this.text().replace('\u00a0', ' ').trim()

/**
 * 取属性值并做非空判断。
 *
 * @param attribute 属性名
 *
 * @return 去掉首尾空白后的属性值，缺失或为空时返回 null
 */
fun Element.attrOrNull(attribute: String): String? =
    attr(attribute).trim().takeIf { it.isNotEmpty() }

/**
 * 把 wenku8 的相对资源地址补全为绝对地址。
 *
 * 封面图在不同镜像上可能写成相对路径、协议相对路径（`//img...`）或直接指向图片站，
 * 统一在这里归一化，避免各调用点各自处理。
 *
 * @param baseUrl 当前站点主机，例如 `https://www.wenku8.net`
 *
 * @return 绝对地址，输入为空时返回 null
 */
fun normalizeUrl(baseUrl: String, raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value

        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> baseUrl.trimEnd('/') + value
        else -> baseUrl.trimEnd('/') + "/" + value
    }
}
