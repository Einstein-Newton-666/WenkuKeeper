package io.github.lnrplugin.wenkukeeper

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import io.github.lnrplugin.wenkukeeper.cloud.CloudSyncEngine
import io.github.lnrplugin.wenkukeeper.migrate.MigrationEngine
import io.github.lnrplugin.wenkukeeper.source.PluginSettingsRegistry
import io.github.lnrplugin.wenkukeeper.source.WenkuKeeperDataSource
import io.github.lnrplugin.wenkukeeper.ui.MigrationViewModel
import io.github.lnrplugin.wenkukeeper.ui.SiteSyncViewModel
import io.github.lnrplugin.wenkukeeper.ui.SyncViewModel
import io.github.lnrplugin.wenkukeeper.ui.UpdateViewModel
import io.github.lnrplugin.wenkukeeper.ui.WenkuKeeperPage
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.plugin.PluginContext
import io.nightfish.lightnovelreader.api.userdata.UserDataDaoApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 文库管家（WenkuKeeper）插件入口。
 *
 * 本插件提供四项能力：
 * 1. **wenku8 数据源**——把 www.wenku8.net 上的小说检索、详情、目录与正文对接到宿主，
 *    注册为独立数据源 [WenkuKeeperDataSource]；
 * 2. **本地数据上云**——把书架、阅读进度、插件设置与一份可读的阅读记录上传到用户自己的
 *    WebDAV 空间或 GitHub 私有仓库，并可随时拉回恢复，见 [CloudSyncEngine]；
 * 3. **书库迁移**——把书架里的书（含阅读进度）迁到当前激活的另一个数据源，见 [MigrationEngine]；
 * 4. **站点账号同步（可选，默认关闭）**——把书架与书签写进用户自己的 wenku8 账号，
 *    见 [io.github.lnrplugin.wenkukeeper.site.Wenku8SiteClient]。
 *
 * 宿主只会加载**一个**带 [Plugin] 注解且实现 [LightNovelReaderPlugin] 的类作为入口。
 *
 * 构造器参数由宿主注入：宿主会挑选一个所有参数类型都在其注入表内的构造器。这里只使用
 * `Context`、[UserDataRepositoryApi]、[UserDataDaoApi]、[BookshelfRepositoryApi]、
 * [BookRepositoryApi]、[LocalBookDataSourceApi] 与 [PluginContext]，它们都由宿主提供。
 *
 * @param context 应用上下文，由宿主注入
 * @param userDataRepository 用户数据仓库，用于读写插件设置
 * @param userDataDao 用户数据底层访问接口，云端恢复时用于写回原始记录
 * @param bookshelfRepository 书架仓库，云端备份与恢复书架
 * @param bookRepository 书本仓库，云端备份与恢复阅读进度
 * @param localBookDataSource 本地书库，用于把书本 id 解析成书名（阅读记录用）
 * @param pluginContext 插件运行时上下文
 */
@Suppress("unused")
@Plugin(
    name = "文库管家",
    version = 2,
    versionName = PluginConstants.PLUGIN_VERSION_NAME,
    author = "LightNovelReader 插件社区",
    description = "wenku8 数据源 + 书架/阅读进度云备份 + 书库迁移 + 可选的站点账号同步",
    // 插件更新地址。⚠️ 实测：宿主当前**从不读取**这个字段——它只被搬进 PluginMetadata，
    // 而真正的更新检查（PluginUpdateCheckRepository）查的是官方插件商店
    // `plugins.nariko.org/api/plugins?id=<包名>`。因此这里填得再对，未上架商店的插件
    // 也不会被宿主提示更新；插件自己查 Releases 的逻辑见 update/UpdateChecker.kt。
    // 仍然填上是因为：这个值语义上是对的，且万一宿主将来开始读它就有用。
    updateUrl = PluginConstants.PROJECT_URL,
    apiVersion = PluginConstants.API_VERSION
)
class WenkuKeeperPlugin(
    private val context: Context,
    private val userDataRepository: UserDataRepositoryApi,
    private val userDataDao: UserDataDaoApi,
    private val bookshelfRepository: BookshelfRepositoryApi,
    private val bookRepository: BookRepositoryApi,
    private val localBookDataSource: LocalBookDataSourceApi,
    private val webBookDataSourceManager: WebBookDataSourceManagerApi,
    @Suppress("unused") private val pluginContext: PluginContext
) : LightNovelReaderPlugin {

    companion object {
        private const val TAG = "WenkuKeeperPlugin"

        /**
         * 自动上传的轮询间隔。
         *
         * 宿主没有向插件暴露「一次阅读结束」的事件，因此自动上传只能按固定间隔检查并上传。
         * 30 分钟是在「及时性」与「请求量」之间的折中：快照体积很小，但没必要更频繁。
         */
        private const val AUTO_UPLOAD_INTERVAL_MILLIS = 30 * 60 * 1000L
    }

    /** 自动同步所用的协程作用域；随插件实例存活。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 云端同步引擎。
     *
     * 延迟创建，避免在宿主初始化阶段就构造依赖对象。
     */
    private val cloudSyncEngine: CloudSyncEngine by lazy {
        CloudSyncEngine(
            context = context,
            userDataRepository = userDataRepository,
            userDataDao = userDataDao,
            bookshelfRepository = bookshelfRepository,
            bookRepository = bookRepository,
            bookTitleProvider = ::resolveBookTitle
        )
    }

    /**
     * 解析书名，供「阅读记录」Markdown 使用。
     *
     * 先查宿主的本地书库（一次带索引的数据库读取，绝大多数在书架里的书都命中），
     * 只有本地没有缓存时才回落到远端数据源取一次。取不到就返回 null，由调用方退回显示书本 id
     * ——阅读记录的生成不应该因为个别书本取不到标题而失败。
     *
     * @param id 书本 id
     *
     * @return 书名，本地与远端都取不到时为 null
     */
    private suspend fun resolveBookTitle(id: String): String? {
        val cached = runCatching { localBookDataSource.getBookInformation(id)?.title }
            .getOrNull()
        if (!cached.isNullOrBlank()) return cached

        // 本地没有缓存：走一次「先本地后远端」的数据流，取最后一次发射的结果。
        return runCatching {
            bookRepository.getBookInformationFlow(id).last().get()?.title
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 插件页面的状态持有者。
     *
     * 同样延迟创建；[SyncViewModel] 内部自行管理协程作用域，不依赖宿主的 ViewModelStore。
     */
    private val syncViewModel: SyncViewModel by lazy {
        SyncViewModel(
            context = context,
            userDataRepository = userDataRepository,
            cloudSyncEngine = cloudSyncEngine
        )
    }

    /**
     * 书库迁移引擎。
     *
     * 导入的**目标**是当前激活的数据源，**来源**是书架里已有的书：插件只能访问激活源
     * （[WebBookDataSourceManagerApi] 只暴露 `getWebDataSource()`），而本地书库缓存不带来源，
     * 因此可以读到书架里任意来源的书目。用户切到目标源后即可导入。
     */
    private val migrationEngine: MigrationEngine by lazy {
        MigrationEngine(
            context = context,
            localBookDataSource = localBookDataSource,
            bookRepository = bookRepository,
            bookshelfRepository = bookshelfRepository,
            webBookDataSourceManager = webBookDataSourceManager,
            userDataRepository = userDataRepository,
            pluginContext = pluginContext
        )
    }

    /** 迁移页面的状态持有者；与 [syncViewModel] 一样自行管理协程作用域。 */
    private val migrationViewModel: MigrationViewModel by lazy {
        MigrationViewModel(
            context = context,
            userDataRepository = userDataRepository,
            migrationEngine = migrationEngine,
            bookshelfRepository = bookshelfRepository
        )
    }

    /**
     * wenku8 站点同步的状态持有者。
     *
     * 与云端备份是两件不同的事：这里写的是**用户的站点账号**（书架与书签），
     * 云端备份写的是用户自己的 WebDAV / GitHub 空间。默认关闭，见
     * [PluginSettings.SITE_SYNC_ENABLED]。
     */
    private val siteSyncViewModel: SiteSyncViewModel by lazy {
        SiteSyncViewModel(
            context = context,
            userDataRepository = userDataRepository,
            bookshelfRepository = bookshelfRepository,
            bookRepository = bookRepository
        )
    }

    /**
     * 更新检查的状态持有者。
     *
     * 宿主的更新检查只认官方插件商店，未上架的插件永远不会被提示；这里直接读插件自己
     * GitHub 仓库的 Releases，见 [io.github.lnrplugin.wenkukeeper.update.UpdateChecker]。
     */
    private val updateViewModel: UpdateViewModel by lazy { UpdateViewModel() }

    override fun onLoad() {
        Log.i(TAG, "WenkuKeeper 插件已加载，数据源 id = ${PluginConstants.WEB_DATA_SOURCE}")
        // 数据源由宿主在本回调之后才实例化，因此把仓库登记到共享持有者，供其读取插件设置。
        PluginSettingsRegistry.attach(userDataRepository)
        startAutoSync()
    }

    /**
     * 按用户设置在后台启动自动同步。
     *
     * - **自动恢复**：每次插件加载后拉取云端最新快照并合并（只增不减），因此每次冷启动最多一次；
     * - **自动上传**：按 [AUTO_UPLOAD_INTERVAL_MILLIS] 周期性调用 `syncNow()`，它会先把云端快照
     *   合并进来再上传，避免多设备之间互相覆盖。
     *
     * 两项设置都在这里读取一次：设置项改动后需要重新加载插件（重启宿主）才会生效，这一点已在
     * 插件页面的说明中写明。
     */
    private fun startAutoSync() {
        scope.launch {
            // booleanUserData(...).get() 返回可空值，这里用 runCatching 兜住底层异常，
            // 再经 getOrElse 退回默认值：读取设置失败不应该阻止插件加载。
            val autoDownload = runCatching {
                userDataRepository.booleanUserData(PluginSettings.AUTO_DOWNLOAD)
                    .get() ?: PluginSettings.DEFAULT_AUTO_DOWNLOAD
            }.getOrElse { PluginSettings.DEFAULT_AUTO_DOWNLOAD }

            val autoUpload = runCatching {
                userDataRepository.booleanUserData(PluginSettings.AUTO_UPLOAD)
                    .get() ?: PluginSettings.DEFAULT_AUTO_UPLOAD
            }.getOrElse { PluginSettings.DEFAULT_AUTO_UPLOAD }

            if (autoDownload) {
                cloudSyncEngine.pullLatest()
                    .onErr { Log.w(TAG, "自动恢复失败：${it.message}") }
            }

            if (!autoUpload) return@launch
            while (currentCoroutineContext().isActive) {
                delay(AUTO_UPLOAD_INTERVAL_MILLIS)
                cloudSyncEngine.syncNow()
                    .onErr { Log.w(TAG, "自动上传失败：${it.message}") }
            }
        }
    }

    override fun onUnload() {
        Log.i(TAG, "WenkuKeeper 插件已卸载")
        // 关闭同步引擎缓存的远端客户端（WebDAV 或 GitHub），释放其连接池；再次使用时引擎会自动重建。
        runCatching { cloudSyncEngine.close() }
            .onFailure { Log.w(TAG, "关闭同步引擎失败：${it.message}") }
    }

    @Composable
    override fun PageContent(paddingValues: PaddingValues) {
        // remember 保证重组时不会反复新建状态持有者。
        val viewModel = remember { syncViewModel }
        val migration = remember { migrationViewModel }
        val siteSync = remember { siteSyncViewModel }
        val update = remember { updateViewModel }
        WenkuKeeperPage(
            paddingValues = paddingValues,
            userDataRepository = userDataRepository,
            syncViewModel = viewModel,
            migrationViewModel = migration,
            siteSyncViewModel = siteSync,
            updateViewModel = update
        )
    }
}
