package io.github.lnrplugin.wenku8plus.source

import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

/**
 * 插件内部共享的用户数据仓库持有者。
 *
 * 宿主在加载插件时先调用插件入口的 `onLoad()`，之后才实例化 `@WebDataSource` 标注的
 * 数据源类（见宿主 `PluginManager.loadPlugin`）。因此数据源无法通过构造器拿到用户数据
 * 仓库，只能经由这个进程内的持有者取用。
 *
 * 该对象只保存一个引用，不持有 Context 或任何网络资源。
 */
object PluginSettingsRegistry {

    @Volatile
    private var repository: UserDataRepositoryApi? = null

    /**
     * 由插件入口在 `onLoad()` 中登记用户数据仓库。
     *
     * @param repository 宿主注入的用户数据仓库
     */
    fun attach(repository: UserDataRepositoryApi) {
        this.repository = repository
    }

    /**
     * 读取已登记的用户数据仓库。
     *
     * 数据源在插件入口登记之前被提前实例化的极端情况下会返回 null，此时调用方应当
     * 退回默认设置而不是抛出异常。
     *
     * @return 已登记的用户数据仓库，未登记时为 null
     */
    fun getRepository(): UserDataRepositoryApi? = repository
}
