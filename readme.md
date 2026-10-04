# 文库管家 (WenkuKeeper) — LightNovelReader 插件

把 [wenku8 轻小说文库](https://www.wenku8.net/) 的小说数据接入
[LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)，把你的书架与阅读记录
备份到**你自己的** WebDAV 空间或 GitHub 私有仓库，并支持书库迁移与可选的站点账号同步。

> 插件列表里显示的名字是**文库管家**；`WenkuKeeper` 是它的英文名，也是包名与仓库名所用的标识。

面向宿主的插件 API 版本：**4**（`ApiMetadata.API_VERSION = 4`）。

---

## 功能

1. **wenku8 数据源** —— 详情 / 目录 / 正文 / 搜索 / 探索页，注册为独立数据源；
2. **云端备份** —— 书架、阅读进度、插件设置，可存 WebDAV 或 GitHub；
3. **阅读记录** —— 额外生成一份人可读的 `READING_LOG.md`，在 GitHub 上可直接查看；
4. **书库迁移** —— 把书架里的书（含阅读进度）迁到当前激活的另一个数据源；
5. **站点账号同步（可选）** —— 把书架与书签写进你自己的 wenku8 账号。

### 1. 从 wenku8 同步小说数据

插件注册一个名为 `WenkuKeeper` 的网络数据源（标识符 `wenkukeeper:wenku8`），提供：

| 能力 | 说明 |
| --- | --- |
| 书本详情 | 书名、副标题、封面、作者、简介、标签、文库分类、字数、最后更新、完结状态 |
| 卷与目录 | 按卷分组的章节树，含卷标题与章节标题 |
| 章节正文 | 段落与插图；自动给出上一章 / 下一章 |
| 搜索 | 按书名、按作者；支持多页结果与「唯一结果直接跳转」 |
| 探索页 | 首页推荐位、轻小说列表、四个排行榜、完结全本、按标签浏览 |
| 标签跳转 | 书页里点击 wenku8 标签可直接进入对应探索展开页 |

#### 可选的会话 Cookie

站点现在对绝大多数请求都要求带登录 Cookie，并且这个 Cookie 里同时携带 **Cloudflare 的放行
凭证**——不带它时站点直接返回 `403`，表现就是「数据源不可用」。因此插件页面里有一个
**「wenku8 账号 (可选)」→「会话 Cookie」**，把浏览器里的完整 Cookie 粘进去即可：

```
PHPSESSID=...; jieqiUserInfo=...; jieqiVisitInfo=...
```

获取方式：浏览器登录 `www.wenku8.net` → F12 → Network → 任意请求 → 复制 `Cookie` 请求头的完整值。

几条设计取舍：

- **不做用户名密码登录，也不内置任何凭据。** 用户自己登录、自己复制，插件只负责带上它。
- **留空即维持无凭据模式**，行为与从前完全一致（能读免登录章节，但可能被 Cloudflare 挡）。
- **属于敏感项**：只保存在宿主数据库（未加密），**绝不写入云端快照**——快照是明文存放的，
  写进去等于把会话泄露到云端，用别的设备恢复还会顶掉那台的登录态。
- 站点会话会过期，过期后重新复制一次即可；插件每 5~120 秒重新读取该设置，改完无需重启。

> **能不能靠它绕过 Cloudflare，尚未验证。** 关键在于该 Cookie 里是否含 `cf_clearance`，
> 以及 Cloudflare 是否把它绑定到与插件请求一致的出口 IP / User-Agent。开发所用的网络下
> 站点对所有客户端都返回 403（连宿主内置的、带硬编码 Cookie 的数据源也取不到页面），
> 因此这一点**在本机无法验证**。请把它理解为「给请求补上凭据的通道」，而不是「保证解除封锁」。
> 若填了仍然 403，那就是站点侧的拦截，插件无能为力。

实现上的几个要点：

- **不内置任何账号凭据。** 无需登录即可阅读的章节都能正常获取；VIP 章节在未登录时站点
  本就返回受限页面，插件不会为此分发他人账号信息。
- **按 GB18030 解码。** 站点响应头声明 GBK，但 `•`、`〜` 等字符是以 GB18030 独有的四字节
  序列传输的，交给响应头声明的字符集解码会碎成乱码。
- **多镜像自动切换。** `wenku8.net` / `wenku8.cc` / `wenku8.com` 依次探测，优先使用可用者；
  也可以在插件页面里固定一个镜像。
- **请求限流。** 站点限制两次搜索至少间隔 5 秒，命中提示后插件会等待并重试同一页。
- **图片防盗链。** 通过数据源的 `imageHeader` 为封面与插图补上 `Referer`。

### 2. 把本地数据上传到云端（WebDAV 或 GitHub）

插件页面里选择后端并填好配置后，可以：

- **立即上传到云端** —— 先把云端最新快照合并进来（只增不减），再把书架、阅读进度与插件设置
  打包上传；
- **从云端恢复最新备份** —— 拉取最新快照并合并回本地；
- **测试连接** —— 校验配置是否可用；
- 可选**自动上传 / 自动恢复**，以及**保留快照数量**（超出后自动删除最旧的）。

两种后端共用同一套快照格式、合并策略与保留策略，只是「远端」不同：

| 后端 | 需要准备 | 说明 |
| --- | --- | --- |
| **WebDAV** | 服务器地址 + 账号 + 应用密码 | 坚果云、Nextcloud、群晖、Alist、自建 WebDAV 均可；支持 Basic 与 Digest 认证 |
| **GitHub** | 私有仓库 + Fine-grained PAT | 走 Contents API；token 需要该仓库的 `Contents: Read and write` 权限 |

### 3. 阅读记录（READING_LOG.md）

上传时会在远端根目录额外写一份 `READING_LOG.md` —— 一份**人可读**的阅读历史：

```markdown
# 阅读记录

生成时间：2026-10-03 21:40 ｜ 共 12 本 ｜ 已完结 5 本 ｜ 累计 47 小时 12 分钟

| 书名 | 进度 | 阅读时长 | 最后阅读 | 书架 |
| --- | --- | --- | --- | --- |
| 书名 A | 100% | 6 小时 3 分钟 | 2026-10-02 23:11 | 默认 |
| 书名 B | 42% | 1 小时 20 分钟 | 2026-09-28 08:05 | 在读 |
```

放在 GitHub 上可以直接渲染，也能当纯文本读。书名优先取宿主的本地书库缓存，本地没有才去远端
取一次；取不到就退回显示书本 id。

> **为什么导出的是阅读记录而不是小说正文**
>
> 正文导出的三个问题：① wenku8 的内容有版权，推到云端属于再分发；② 必须先逐章
> `preloadChapterContent`，一本长篇就是几百次请求；③ 体积大、逐章写入会产生海量 commit。
> 阅读记录是你自己的数据，没有这些问题，而且本地现成、体积极小。

关于自动同步：

- **自动恢复**在每次插件加载后执行一次，拉取云端最新快照并合并（不覆盖、不删除本机数据）；
- **自动上传**每 30 分钟调用一次「合并后上传」。宿主没有向插件暴露「一次阅读结束」事件，
  因此无法做到退出即上传，只能按固定间隔轮询；
- 「首选镜像」与自动同步开关在**插件加载时读取一次**，改动后需要重新加载插件（重启宿主）
  才会生效；「启用探索页」由数据源的轮询循环刷新，保存后约 2 分钟内自动生效。

如果快照生成过程中有某一段数据读取失败，插件会照常上传，但状态栏会追加
「注意：N 项数据未能读取，本次备份不完整」的提示 —— 看到它说明这次备份是残缺的，请重试。

云端文件是自描述的，文件名形如 `lnr-20261003-201500.json.gz`：

```
"LNRW" (4 字节魔术头) + 格式版本 (1 字节) + gzip(JSON)
```

JSON 结构（`CloudSnapshot`）：

```jsonc
{
  "version": 1,
  "createdAtEpochMillis": 1790000000000,
  "deviceLabel": "Android",
  "bookshelves": [ { "id": 1, "name": "默认", "allBookIds": ["1712"], "pinnedBookIds": [], "updatedBookIds": [] } ],
  "readingData": [ { "id": "1712", "readingProgress": 0.42, "lastReadChapterId": "12345" } ],
  "userData":     [ { "path": "plugin.wenkukeeper.cloud.webdavUrl", "group": "plugin.wenkukeeper.cloud", "type": "String", "value": "..." } ]
}
```

恢复采用**只增不减**的合并策略：阅读进度取较大值、各章历史与当前进度逐章取最大值、书架的
书本 id 取并集；`StringList` 类型的用户数据按逗号拆分后取并集去重（与宿主的合并语义一致），
其它类型以云端值为准。因此恢复不会把本地进度改小。

**WebDAV 账号与密码不会被写入快照。** 快照本身是明文存放的（只有 gzip 压缩、没有加密），
把凭据写进去等于把密码又存了一份到服务器上；而且用别的设备恢复时会把本机账号悄悄换掉。
因此这两项既不采集也不应用 —— 在新设备上使用云端备份时，请在新设备上重新填写一次账号密码。

> 如果你使用过更早的、会把凭据写进快照的版本，云端那些旧文件里仍然含有明文密码，直到它们
> 被「保留快照数」（默认 10）逐步淘汰。建议直接登录 WebDAV 服务把这些旧文件删掉，并更换一次
> 应用专用密码。

> **关于备份范围（请务必了解）**
>
> 宿主暴露给插件的 API 只能枚举**书架、阅读数据**，以及插件自己写入的设置项。
> 没有公开 API 可以枚举宿主数据库中任意 `user_data` 行、已缓存的书本详情或章节正文。
> 因此本插件的云端快照**不包含**：宿主的全部设置项、已下载/缓存的章节正文、阅读统计明细。
>
> 如果你需要**完整**的本地数据库备份，请使用宿主自带的
> 「设置 → 数据 → 导出数据」，它导出的文件可以用「导入数据」原样还原；本插件解决的是
> **自动化异地备份 / 跨设备同步书架与阅读进度**这一场景。

### 4. 书库迁移

**目标永远是宿主里当前激活的数据源，来源是书架里任意来源的书。** 用来解决「换站」：
你在 A 站积累了一批书和阅读进度，想搬到 B 站去。

流程分四步，都在插件页面的「书库迁移」一节里（分节标题与界面一致）：

| 步骤 | 做什么 | 需要联网 |
| --- | --- | --- |
| **1. 导出书架** | 把书目清单写成一份计划；范围可选「全部书架」或某一个书架 | ❌ 纯本地 |
| **2. 开始匹配** | 在激活源里逐本搜索候选，再取详情，按书名与作者打分 | ✅ |
| **3. 采纳全部高置信** | 也可逐条**采纳 / 跳过 / 重新匹配**，候选行显示档位、分数与打分理由 | ❌ |
| **4. 导入到新书架** | 在目标源里**新建一个书架**放进去（默认名「迁移导入」） | ✅ |

几个关键设计：

- **原书架完全不动。** 导入只在目标数据源里新建一个书架，不会删除或改动原来的书架与书。
- **匹配打分**：书名与作者共同计分，**≥ 80 分算高置信**（可一键全采纳），**≥ 50 分为中**，
  其余为低。一边作者为空时只给很低加分——不因为「对方没写作者」就判成不同的书。
- **简繁字形归一**：来源与目标常分属简体站与繁体站（例如 `wenku8.net` → `tw.linovelib.com`），
  比对前统一转成繁体，否则逐字相似度会把本该高置信的匹配压成低置信。
- **计划落盘，中断可续**。计划写在插件自己的数据目录
  （`plugins/<插件包名>/data/migration-plan.json`）；已经搜过的书下次会跳过，所以几百本跑到
  一半断了可以直接接着跑。也可以随时「清除迁移计划」。
- **阅读进度只搬不依赖章节结构的部分**：整体进度、累计时长、最后阅读时间取较大值；
  **章节级进度与最后阅读章节保持目标书自己的值**——两个站的章节 id 不同，复制过去只会把进度写坏。
- **没有本地缓存信息的书会被跳过**：宿主只缓存你打开过的书，导出时会提示
  「N 本书没有本地缓存信息，已跳过（先在原数据源里打开一次即可缓存）」。
- 目标书本必须能从激活源取到详情，取不到就跳过并计入失败——宿主用详情的 `lastUpdated`
  维护更新提醒表，凭空造一个空对象会把那张表写坏。

> **注意**：迁移依赖激活源**联网搜索**，因此受站点限流影响（例如 wenku8 要求两次搜索
> 间隔 ≥ 5 秒），几百本书会比较慢。这是站点侧约束，插件无法绕过。

### 5. 同步到 wenku8 站点账号（可选）

**默认关闭，且只在按下按钮时才发请求。** 它解决的是"在手机上读到哪儿，回到网站上接着读"。

站点的书架模型很简单：**一本书一条记录，`cid` 就是书签**——也就是「我的书架」里那一列。
三个入口都是 GET，靠会话 Cookie 认证（以下路径来自真实浏览器抓包，不是推测）：

| 操作 | 请求 |
| --- | --- |
| 读取书架 | `/modules/article/bookcase.php` |
| **加入书架 / 更新书签** | `/modules/article/addbookcase.php?bid={书本id}[&cid={章节id}]` |
| 从书架移除 | `/modules/article/bookcase.php?delid={内部id}` |

关键点：**`addbookcase.php` 一次调用同时完成"加入书架"和"设置书签"**——带上 `cid`
就把书签记到那一章，同一本书再调一次就是**移动书签**。所以「推送到站点书架」对每本书
调用一次，`cid` 取宿主记录的 `lastReadChapterId`，没有阅读记录的书就只加书、不设书签。

页面上的两个按钮：

- **推送到站点书架** —— 逐本推送，每本之间等 1.5 秒（站点有限流）。书架上**只有书本 id
  是纯数字**的书会被处理——那是 wenku8 的书本 id，可直接当 `bid`；其它数据源的书会
  计入"已跳过"，不会静默丢弃。
- **读取站点书架** —— 列出站点书架上每本书的书签。**这是确认推送真的生效的唯一方式**：
  站点用重定向表达结果，"请求成功"不等于"操作生效"。

三道闸门，任何一道不满足都不会发出请求，并给出可操作的提示：

1. **总开关**（默认关）—— 关闭时只提示"站点同步未开启"；
2. **会话 Cookie** —— 未填写时提示去「wenku8 账号 (可选)」粘贴；
3. **非 2xx 视为失败** —— Cloudflare 挑战页与未登录页都不是 2xx，会被明确报出来，
   而不是被当成"站点书架上没有书"。

刻意**不做**的：把站点书架的书加进宿主书架。那需要为每本书取到 `BookInformation`
（宿主用它的 `lastUpdated` 维护更新提醒表），站点不可达时凭空造对象会把那张表写坏。

> **验证状态（请勿夸大）**
>
> - 接口路径、`bid`/`cid`/`delid` 的含义、书架表格的 DOM 结构：**来自真实抓包**，可信；
> - 书架页解析逻辑：用抓到的真实 DOM 做成离线 fixture 校验，覆盖"表头有/无复选框格"
>   与"表头缺失"三种情况，见 `tools/check_site_shelf_parser.py`；
> - **端到端（真的把书推进站点、书签真的变了）没有跑过** —— 开发所用的网络下站点对所有
>   客户端都返回 403，只有浏览器能过。请在能用浏览器打开站点的网络下自行验证；
> - **"书签非空"的样本在抓包里没有出现**（抓到的两本书书签都是空的），因此书签的读取
>   路径只由合成的 fixture 覆盖，真实输出仍需实测确认。

---

## 安装

### 方式一：直接安装构建好的插件

1. 从 Release 或构建产物中拿到 `plugin-debug.apk.lnrp`；
2. 在 LightNovelReader 中打开「设置 → 插件管理 → 从文件安装」，选择该 `.lnrp` 文件；
   （也可以把文件后缀改回 `.apk` 后用系统安装器安装）
3. 安装后回到插件管理页启用 **WenkuKeeper**；
4. 在「设置 → 数据源」里把数据源切换为 **WenkuKeeper**。

### 方式二：自行构建

环境要求：**JDK 17**、Android SDK（`compileSdk 37`、`targetSdk 37`、`minSdk 24`）。

如果你本机还没有这些工具，仓库带了一键脚本，会把便携版 JDK 与 Android 命令行工具下载到
**共享工具链目录**（默认 `<工作区>/project/vm/`，旧检出回退到插件仓库下的 `.build-tools/`；
两者都在 gitignore 之外，不随仓库分发），安装 SDK 组件并直接构建：

```powershell
pwsh -File tools/setup-and-build.ps1                 # 构建 debug 插件
pwsh -File tools/setup-and-build.ps1 -Variant Release
```

脚本会依次准备 JDK、Android SDK（用 `tools/fetch_android_sdk.py` 直接下载，不依赖
`sdkmanager`）并调用 Gradle。若 `setup-and-build.ps1` 的 sdkmanager 一步失败，可手动执行：

```powershell
python tools/fetch_android_sdk.py --dest <SDK 目录> --api 37.2 --build-tools 37.0.0
pwsh -File tools/build-local.ps1                     # 环境已就绪时只构建
```

已有环境时直接用 Gradle：

```bash
./gradlew :plugin:assembleDebug
```

产物：`plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp`（约 15.9 MB；debug 变体未开启
压缩代码/资源，Release 变体会小很多）。

> **受限环境**：如果本机无法写系统临时目录、或 Java 信任库验证不了
> `services.gradle.org`（报 `PKIX path building failed`），可以用
> `pwsh -File tools/build-local.ps1`。该脚本把 `GRADLE_USER_HOME`、`TMP` 等全部指到工作区内，
> 并使用共享工具链目录里已下载的 Gradle 发行包。若该 zip 不存在，脚本会提示改用官方地址。
>
> 工具链位置由 `tools/toolchain.ps1` 统一解析：先找 `<工作区>/project/vm/`，找不到再回退到
> `<插件仓库>/.build-tools/`。要换位置，改这一个文件即可。

连接设备后可以直接构建、安装并重启宿主：

```bash
./gradlew runReleaseHostWithDebugPlugin    # 装到正式版宿主
./gradlew runDebugHostWithDebugPlugin      # 装到 debug 版宿主
```

> 依赖 `io.nightfish.lightnovelreader:api:0.4-SNAPSHOT` 与
> `io.nightfish.lightnovelreader:compiler:0.4-SNAPSHOT` 来自
> `https://maven.nariko.org/release`，已在 `settings.gradle.kts` 中配置。

---

## 使用

### 配置数据源

宿主会把插件贡献的数据源当作独立数据源。它与内置的 `Wenku8` 数据源**共存但相互独立**：
两者使用不同的标识符，因此书架条目不会串味，你可以随时在设置里切换。

> 插件页面中的「首选镜像」留空表示自动探测。

### 配置云端备份

**用 WebDAV（以坚果云为例）**

1. 在坚果云里开启第三方应用管理，生成一个**应用密码**；
2. 打开 LightNovelReader 的插件页面 → 云端同步：
   - 后端：WebDAV
   - 服务器地址：`https://dav.jianguoyun.com/dav/`
   - 用户名：你的坚果云账号
   - 密码：上一步生成的应用密码
   - 远程目录：`LightNovelReader`（会自动创建）
3. 点「测试连接」，成功后即可「立即上传到云端」。

Nextcloud、群晖、Alist、自建 Nginx+WebDAV 等同理，填入对应的 WebDAV 根地址。

**用 GitHub**

1. 在 GitHub 上建一个**私有**仓库（例如 `my-reading-log`），可以勾选自动创建 README；
2. 生成一枚 **Fine-grained personal access token**：Settings → Developer settings →
   Personal access tokens → Fine-grained tokens。仓库访问选 **Only select repositories**
   并选中上一步的仓库，权限只给 **Contents: Read and write**；
3. 插件页面 → 云端同步：
   - 后端：GitHub
   - 仓库：`你的用户名/仓库名`
   - 令牌：上一步生成的 PAT
   - 分支：`main`
   - 目录：`LightNovelReader`
4. 点「测试连接」，成功后即可上传。

> 建议用私有仓库：`READING_LOG.md` 虽然不含正文，但会暴露你在读哪些书。
>
> ⚠️ WebDAV 密码与 GitHub 令牌都保存在宿主的本地数据库中，**未加密**。请务必使用应用专用
> 密码 / 细粒度令牌，不要填写主账号密码。这两项**不会**被写进上传的快照。

---

## 项目结构

```
plugin/src/main/kotlin/io/github/lnrplugin/wenkukeeper/
├── PluginConstants.kt            数据源标识、镜像、标签、发布仓库等常量
├── PluginSettings.kt             所有用户数据键与默认值（唯一来源）
├── WenkuKeeperPlugin.kt          插件入口（@Plugin + 依赖注入 + 页面）
├── PluginDiscoveryReceiver.kt    响应宿主的插件发现广播
├── tools/
│   ├── Wenku8HttpClient.kt       Ktor + CIO 网络层：GB18030 解码、限流、镜像探测、可选 Cookie
│   └── JsoupXPath.kt             安全的 XPath / 文本 / URL 工具
├── source/
│   ├── WenkuKeeperDataSource.kt  WebBookDataSource 实现：详情 / 目录 / 正文
│   ├── PluginSettingsRegistry.kt 在入口与数据源之间共享用户数据仓库
│   ├── search/WenkuKeeperSearchProvider.kt   搜索（GB2312 关键词 + 5 秒间隔重试）
│   └── explore/                  探索卡片页与展开页数据源
├── site/
│   ├── Wenku8SiteShelf.kt        站点书架行模型与解析器（DOM 结构来自真实抓包）
│   └── Wenku8SiteClient.kt       站点书架读写：bookcase.php / addbookcase.php
├── cloud/
│   ├── remote/
│   │   ├── RemoteStore.kt        远端存储抽象：WebDAV 与 GitHub 共用的接口与错误模型
│   │   └── GitHubStore.kt        GitHub Contents API 实现（PAT 认证）
│   ├── WebDavClient.kt           WebDAV 客户端（PROPFIND/PUT/GET/MKCOL/DELETE + Basic/Digest）
│   ├── SnapshotCodec.kt          快照模型、编解码与合并策略
│   ├── ReadingLogBuilder.kt      把人可读的阅读记录渲染成 Markdown
│   └── CloudSyncEngine.kt        上传 / 列表 / 下载 / 恢复 编排（后端无关）
├── migrate/
│   ├── MigrationPlan.kt          迁移计划的模型、持久化与书名/作者打分
│   ├── MigrationEngine.kt        导出 / 匹配 / 采纳 / 导入四阶段编排
│   └── ChineseVariant.kt         简繁归一（经宿主类加载器反射取 android.icu）
├── update/
│   └── UpdateChecker.kt          读本仓库的 GitHub Releases 做更新检查
└── ui/
    ├── WenkuKeeperPage.kt        插件页面（Compose）
    ├── SyncViewModel.kt          云端同步状态与操作
    ├── MigrationViewModel.kt     书库迁移状态与操作
    ├── MigrationSection.kt       迁移分节的界面
    ├── SiteSyncViewModel.kt      站点账号同步状态与操作
    ├── UpdateViewModel.kt        更新检查状态
    ├── BackendChooserDialog.kt   后端选择对话框
    └── SimpleTextDialog.kt       文本输入对话框
```

---

## 更新

**宿主的插件更新检查只认官方插件商店**（`plugins.nariko.org/api/plugins?id=<包名>`），
而插件 API 里的 `@Plugin(updateUrl = …)` 宿主**从未读取**——所以没上架商店的插件，
宿主永远不会提示更新。本插件因此**自己查自己的 GitHub Releases**：打开插件页面时自动查一次
（每次进程最多一次），也可以在「关于 → 检查更新」手动重查；有新版本时下方会多出一行
「下载新版本」直接跳到发布页。

> 仓库一个 Release 都没发过时 GitHub 返回 404。插件把这种情况单独说成
> 「仓库还没有发布 Release」而不是报错——它不是故障，别和"连不上"混为一谈。

**发版约定**（不照做用户就收不到提示）：

1. 改 `PluginConstants.PLUGIN_VERSION_NAME`，同时把 `@Plugin(version = )` 与
   `plugin/build.gradle.kts` 里的 `versionCode` 各加 1；
2. tag 用 `v<PLUGIN_VERSION_NAME>`（如 `v1.1.0`）。比较时忽略 `v` 前缀、按 `.`/`-`/`+`/`_`
   切段、每段只取开头数字、段数不同按 0 补齐（因此 `1.1` 与 `1.1.0` 等价）；
3. 在 GitHub 上以该 tag 发布 Release——草稿与预发布不会被 `/releases/latest` 选中。

---

## 发版流程

1. **三处版本号一起改**：`PluginConstants.PLUGIN_VERSION_NAME`、`@Plugin(version = )`、
   `plugin/build.gradle.kts` 的 `versionCode` / `versionName`。
2. **干净构建**：增量构建会累积陈旧 dex（实测同一份源码能差 85 KB），发布前先删掉
   `plugin/build` 与 `build` 再构建，并记下产物的 sha1 / sha256。
3. 更新 `distribution` 分支（见下），让 `plugin.toml` 的 sha1 与新产物一致。
4. 以 tag `v<版本>` 发布 Release，附件挂 `plugin-debug.apk.lnrp`，说明里写上两个校验和。
5. 插件内更新检查读的就是第 4 步的 Release，所以 tag 必须与版本号相等。

### `distribution` 分支

插件市场要求分发文件放在仓库（或分支）的**根目录**，与源码分开，因此本仓库用一条独立的
`distribution` 分支承载：`plugin.toml` + `plugin-debug.apk.lnrp` + `icon.png` + 截图。
它是**独立的根提交**（orphan），与 `main` 没有共同历史；改插件时只更新这一条分支。

`plugin.toml` 的 `[release.download] sha1` 必须与同目录的 `plugin-debug.apk.lnrp` 一致，
否则商店装出来的包校验不过。**这一步连同干净构建都已脚本化**：

```powershell
# 干净构建 → 回写版本号与 sha1 → 打印待提交差异（不提交）
.\tools\make-distribution.ps1

# 确认无误后，提交并推送 distribution 分支
.\tools\make-distribution.ps1 -Push
```

脚本在提交前会自检「`plugin.toml` 声明的 sha1 == 同目录 `lnrp` 的实际 sha1」，不一致直接报错。

> **代价**：`distribution` 分支里有 15 MB 的 `.lnrp`，而 `git clone` 会拉取所有分支的对象，
> 所以克隆本仓库约 15 MB（源码本身只有 0.6 MB）。这是用分支而非独立仓库承载分发包的取舍。

### 上架官方插件市场

宿主的更新检查**只认官方商店**（`@Plugin(updateUrl)` 宿主从不读取，所以插件自己查
Releases，见上一节）。**只有上架后宿主才会提示更新。** 上架步骤：

```bash
git clone https://github.com/dmzz-yyhyy/LightNovelReader-PluginRepository.git
cd LightNovelReader-PluginRepository
git checkout -b add-plugin-wenkukeeper
git submodule add -b distribution \
    https://github.com/Einstein-Newton-666/WenkuKeeper.git \
    plugins/io.github.lnrplugin.wenkukeeper
git add .gitmodules plugins/io.github.lnrplugin.wenkukeeper
git commit -m "Add plugin: 文库管家"
git push origin add-plugin-wenkukeeper
```

然后在官方仓库发起 Pull Request，等审核通过。

---

## 真机验证（Android 模拟器，API 36）

以下结论来自实机运行，而不是静态检查。环境：宿主 1.3.0(debug) + 本插件 1.0.0(Api 4)。

| 环节 | 结果 |
| --- | --- |
| 插件加载 | ✅ `WenkuKeeper 插件已加载，数据源 id = wenkukeeper:wenku8` |
| 数据源注册与切换 | ✅ 宿主数据源列表出现 WenkuKeeper，切换后书架走插件的实现 |
| 插件页面渲染 | ✅ Compose 页面（数据源 / 云端同步 / 迁移）正常显示与滚动 |
| 书架加载 | ✅ 3 本书正常渲染；书本详情取不到时显示宿主的错误页，不崩溃 |
| 迁移 · 1 导出书架 | ✅ 「已导出 3 本书」，计划落盘到 `plugins/<包名>/data/migration-plan.json` |
| 迁移 · 2 开始匹配 | ✅ 「匹配完成：0/3 本找到候选」，站点不可达时逐本优雅失败 |
| 迁移 · 计划持久化 | ✅ 重启宿主后自动重新载入计划，出现「重新匹配」「清除迁移计划」 |
| 站点联网 | ❌ `PROBE https://www.wenku8.net -> HTTP 403`（Cloudflare 校验页） |

**未验证的部分**：匹配成功后的「采纳候选 → 应用到新书架」。这一段要求目标站点可达，而本机
网络下 wenku8 的三个镜像全部不可用（`.net`/`.cc` 返回 403，`.com` 的证书是别的域名）。
**宿主内置的数据源同样取不到页面**，所以这是网络环境限制，不是插件缺陷。

## 开发注意事项（只在真机上才会暴露的坑）

### 1. `kotlin-result` 必须声明为 `compileOnly`

插件 API 的返回类型里用了 `com.github.michaelbull.result.Result`，而宿主自己也带同一份
（两边版本一致，均为 2.3.1）。宿主的 `PluginClassLoader` 是**子加载器优先**，白名单里没有
这个包：插件只要自带一份，宿主拿到的就是「插件的 Result」，而宿主会把它 cast 成
「宿主的 Result」——

```
java.lang.ClassCastException: com.github.michaelbull.result.Result
    cannot be cast to com.github.michaelbull.result.Result
    at ...ProxyPriorityWebBookDataSource$getBookInformation$2(ProxyPriorityWebBookDataSource.kt:15)
```

宿主的未捕获异常处理器随后 `System.exit(1)`，**整个 App 直接退出**。触发门槛极低：书架里
只要有一本来自本插件的书，宿主就会调用 `getBookInformation`。

> 官方模板与 linovelib 插件写的都是 `implementation(libs.kotlin.result)`，照抄会踩到。
> 同理，任何「宿主已提供、又出现在 API 签名里」的库都必须 `compileOnly`。
> 门禁：`tools/check_host_overlap.py <plugin.apk> <host.apk>`。

### 2. 不要在插件里 `import android.*`

AGP 必然会在 APK 里放一个 D8「全局合成类」dex，其中 **824 个 `android.*` 条目只声明
`<clinit>`**，是没有任何真实成员的空壳。插件类加载器子加载器优先，于是插件代码里的
`android.*` 引用会命中空壳而不是平台实现。真机上已经因此出过两次问题：

- OkHttp 调用 `android.net.ssl.SSLSockets.isSupportedSocket` → `NoSuchMethodError`
  → `FATAL EXCEPTION: OkHttp Dispatcher` → 宿主进程死亡（在 `OkHttp Dispatcher` 线程上）；
- `android.icu.text.Transliterator.getInstance` 被 `runCatching` 吞掉，
  **简繁归一静默失效**，跨简繁匹配率下降而没有任何报错。

`android.enableGlobalSyntheticsGeneration` **无法关闭**（AGP 9.2.1 直接报
“It was removed in version 8.1 of the Android Gradle plugin”）。因此：

- 门禁：`tools/check_shadow_imports.py <plugin.apk> <src-dir>`，命中即退出码 1；
- 需要框架能力时，用**宿主（系统）类加载器**反射取类，参考
  `migrate/ChineseVariant.kt` 的写法。

### 3. 手工替换插件文件时，目录是 `dataDir/plugins`

宿主用的是 `appContext.dataDir.resolve("plugins")`，也就是
`/data/user/0/<宿主包名>/plugins/<插件包名>/plugin`，**不是** `files/plugins/`。
放到 `files/` 下不会有任何效果，而且日志一切正常，极易误判成「改了没生效」。

另外，宿主启动时会通过 `PluginDiscoveryReceiver` 的 action 扫描**已安装的同名 App 插件**，
并从那个 App 的 `sourceDir` 重新安装、覆盖本机文件。开发时若既 `adb install` 过插件 APK
又手工替换文件，加载到的可能是旧那一份；用 `adb uninstall <插件包名>` 清掉。

---

## 已知限制

- **备份范围有限**：见上文「关于备份范围」。完整的数据库备份请使用宿主自带的导出功能。
- **自动上传是轮询而非事件驱动**：宿主未暴露「阅读结束」事件，因此自动上传固定每 30 分钟一次，
  且相关开关在插件加载时读取，改动后需重启宿主。
- **缓存时长不可配置**：宿主要在数据源构造完成后立即读取缓存配置（且是在主线程上），此刻
  异步读取尚未完成、同步读取又会阻塞 UI，因此缓存固定为 2 小时（与宿主内置数据源一致）。
- **VIP 章节**：插件不携带账号凭据，因此仅能获取免登录可读的章节。
- **快照格式是插件私有的**：`lnr-*.json.gz` 不能被宿主自带的「导入数据」识别，反之亦然。
  这是有意为之——宿主的导出格式依赖非公开的内部实体类。
- **站点结构变更**：解析基于 wenku8 当前的 HTML 结构。站点改版可能导致解析失败，此时书本
  详情会返回明确的「解析错误」而不是崩溃。
- **Cloudflare 人机校验**：wenku8 位于 Cloudflare 之后。插件使用常规桌面浏览器 UA 请求，
  不执行 JavaScript；若 Cloudflare 对某个出口 IP 弹出「Just a moment...」校验页，请求会收到
  403。**遇到这种情况可以试上面「可选的会话 Cookie」**——在浏览器里过一次校验、把该域名的
  完整 Cookie 复制进插件；能否生效取决于 `cf_clearance` 的绑定条件（见该节的说明），
  **开发环境无法验证**。

  实测补充：在开发所用的网络下，`www.wenku8.net` 与 `www.wenku8.cc` 对**所有**客户端都返回
  403（CIO 与 `Android`/HttpURLConnection 引擎结果相同），`www.wenku8.com` 返回的证书属于
  别的域名；连宿主内置的 OkHttp + 硬编码 Cookie 数据源也取不到页面。也就是说此时的「数据源
  不可用」是网络/站点侧的，插件把它标记为离线是正确行为，**换引擎并不能解决**。

  > 维护者提示：本插件的开发环境出口 IP 正被 Cloudflare 拦截，因此**解析选择器未能在真实
  > 站点上验证**，仅与宿主内置的 wenku8 实现逐条比对。首次在真机使用时建议先打开一本书
  > 确认详情、目录、正文都能正常加载。
- **插件卸载时数据源的轮询无法停止**：插件 API（Api 4）没有为数据源提供停止钩子，因此
  「镜像探测 + 探索页开关刷新」的后台循环会一直运行到进程结束。云同步引擎的 HTTP 客户端
  会在插件卸载时正常关闭。

---

## 免责声明

本插件仅作为客户端访问 wenku8 的公开页面，不存储、不转载、不分发任何小说内容。所有内容
版权归原作者与发布方所有。请在遵守所在地法律与站点服务条款的前提下使用。

## 许可证

MIT
