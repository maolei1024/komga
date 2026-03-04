# Komga × Gorse 推荐系统集成 — 实施文档

> 本文档详细记录了 Komga 与 Gorse 推荐系统的集成实现，包括后端数据同步、推荐 API、Mihon 扩展及 CI 构建流程。

---

## 1. 系统架构总览

```
┌─────────────┐      ┌──────────────┐      ┌───────────────┐
│   Mihon App │◄────►│   Komga 后端  │◄────►│   Gorse 推荐  │
│  (扩展插件)  │      │  (Spring Boot)│      │   (REST API)  │
└─────────────┘      └──────────────┘      └───────────────┘
       │                     │                      │
  热门 → 推荐         事件驱动同步数据         协同过滤推荐
```

**数据流：**
1. Komga 中新增/更新/删除 Series → `GorseEventListener` 自动同步到 Gorse Items
2. 用户阅读完成某本书 → 自动发送 `read` 类型 Feedback 到 Gorse
3. Mihon 扩展请求"热门"列表 → 调用 `GET /api/v1/series/recommended` → Komga 从 Gorse 获取推荐 → 返回 Series 列表

---

## 2. Komga 后端修改

### 2.1 文件清单

所有新增文件位于 `komga/src/main/kotlin/org/gotson/komga/` 下：

| 文件 | 路径 | 说明 |
|------|------|------|
| `GorseSettingsProvider.kt` | `infrastructure/gorse/` | 配置管理（enabled, apiUrl, apiKey, feedbackType），持久化到 `server_settings` 表 |
| `GorseModels.kt` | `infrastructure/gorse/` | 数据模型：`GorseItem`、`GorseFeedback`、`GorseUser` |
| `GorseClient.kt` | `infrastructure/gorse/` | Gorse REST API 客户端（WebClient），封装所有 HTTP 调用 |
| `GorseEventListener.kt` | `infrastructure/gorse/` | 事件监听器 + 批量同步方法 |
| `GorseRecommendationController.kt` | `interfaces/api/rest/` | 推荐 API 端点 `GET /api/v1/series/recommended` |

### 2.2 配置项

通过 `GorseSettingsProvider` 管理，存储在 Komga 数据库 `server_settings` 表：

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `GORSE_ENABLED` | `false` | 是否启用 Gorse 集成 |
| `GORSE_API_URL` | `http://localhost:8087` | Gorse API 地址 |
| `GORSE_API_KEY` | `""` | Gorse API Key |
| `GORSE_FEEDBACK_TYPE` | `read` | 阅读反馈类型 |

### 2.3 GorseClient — API 客户端

封装了以下 Gorse API 调用：

```kotlin
// 单个操作
insertItem(item)       // POST /api/item
updateItem(itemId, item) // PATCH /api/item/{itemId}
deleteItem(itemId)     // DELETE /api/item/{itemId}
insertUser(user)       // POST /api/user

// 批量操作
insertItems(items)     // POST /api/items
insertUsers(users)     // POST /api/users
insertFeedback(feedback) // PUT /api/feedback

// 推荐
getRecommendations(userId, n, offset) // GET /api/recommend/{userId}
```

### 2.4 GorseEventListener — 事件驱动同步

监听 Komga 领域事件，自动同步数据到 Gorse：

| 事件 | 处理逻辑 |
|------|----------|
| `SeriesAdded` | 构建 Item（labels 含 genres/tags/authors）→ `insertItem` |
| `SeriesUpdated` | 重新构建 Item → `updateItem` |
| `SeriesDeleted` | `deleteItem` |
| `ReadProgressChanged` | 阅读完成时（`completed=true`）→ 通过 Book 找到 seriesId → 发送 `read` Feedback |

**Labels 构建逻辑（`buildLabelsForSeries`）：**
```json
{
  "genres": ["doujinshi", "romance"],    // 来自 SeriesMetadata.genres
  "tags": ["comedy", "school"],          // 合并 SeriesMetadata.tags + BookMetadataAggregation.tags
  "authors": ["作者A", "作者B"]          // 来自 BookMetadataAggregation.authors
}
```

**批量同步方法（用于初始化）：**
- `syncAllItems()` — 同步所有 Series 到 Gorse Items（每 100 条一批）
- `syncAllUsers()` — 同步所有 Komga 用户到 Gorse Users
- `syncAllFeedback()` — 同步所有已完成的阅读进度到 Gorse Feedback

> ⚠️ **注意：** 批量同步方法目前需要手动调用或通过其他方式触发，暂未暴露为 REST 端点。

### 2.5 GorseRecommendationController — 推荐 API

```
GET /api/v1/series/recommended?page=0&size=20
```

**处理流程：**
1. 获取当前用户 ID（JWT 认证）
2. 调用 `gorseClient.getRecommendations(userId, n=size*2, offset)` — 多取 2 倍防止过滤后不足
3. 用返回的 seriesId 列表查询 `SeriesDtoRepository` 获取实际 Series 数据
4. 过滤不存在的 Series → 取前 `size` 条 → 返回 `Page<SeriesDto>`

---

## 3. Mihon 扩展（komga-gorse）

### 3.1 概览

一个独立的 Mihon 扩展，可以与原版 Komga 扩展**同时安装**。区别是将"热门"标签页替换为 Gorse 推荐结果。

### 3.2 文件清单

所有文件位于 `komgagorse-extension/` 下：

```
komgagorse-extension/
├── build.gradle                         # Gradle 构建配置
├── res/mipmap-*/ic_launcher.png         # 扩展图标（5 种分辨率）
└── src/eu/kanade/tachiyomi/extension/all/komgagorse/
    ├── KomgaGorse.kt                   # 主源码（核心修改在 popularMangaRequest）
    ├── KomgaGorseFactory.kt            # 源工厂（支持多实例）
    ├── KomgaFilters.kt                 # 筛选器定义
    ├── KomgaUtils.kt                   # 工具方法
    └── dto/
        ├── Dto.kt                      # DTO 数据类
        └── PageWrapperDto.kt           # 分页包装
```

### 3.3 核心修改：popularMangaRequest

**原版 Komga 扩展**的 `popularMangaRequest` — 按字母排序获取 Series：
```kotlin
override fun popularMangaRequest(page: Int): Request = searchMangaRequest(
    page, "", FilterList(SeriesSort(Filter.Sort.Selection(1, true)))
)
```

**Komga-Gorse 扩展** — 替换为调用推荐 API：
```kotlin
override fun popularMangaRequest(page: Int): Request {
    val url = "$baseUrl/api/v1/series/recommended".toHttpUrl().newBuilder()
        .addQueryParameter("page", (page - 1).toString())
        .addQueryParameter("size", "20")
        .build()
    return GET(url.toString(), headers)
}
```

### 3.4 与原版 Komga 扩展的共存

通过以下方式确保两个扩展不冲突：

| 属性 | 原版 Komga | Komga-Gorse |
|------|-----------|-------------|
| 包名 | `eu.kanade.tachiyomi.extension.all.komga` | `eu.kanade.tachiyomi.extension.all.komgagorse` |
| 类名 | `Komga` / `KomgaFactory` | `KomgaGorse` / `KomgaGorseFactory` |
| 显示名 | `Tachiyomi: Komga` | `Tachiyomi: Komga Gorse` |

---

## 4. CI/CD 构建流程

### 4.1 GitHub Actions 工作流

文件：`.github/workflows/build-extension.yml`

**触发条件：**
- `komgagorse-extension/**` 路径下的文件变更
- `.github/workflows/build-extension.yml` 变更
- 手动触发（`workflow_dispatch`）

**构建步骤：**

```
1. Checkout komga 仓库
2. 设置 JDK 17 + Android SDK (platforms;android-35, build-tools;35.0.0)
3. 克隆 keiyoushi/extensions-source（提供构建框架）
4. 复制扩展源码到 extensions-source/src/all/komgagorse/
5. 生成签名密钥（keytool RSA 2048）
6. 构建 Release APK（./gradlew :src:all:komgagorse:assembleRelease）
7. 用 aapt + Python 解析 APK 元数据（包名、版本、标签等）
8. 用 apksigner 提取签名指纹
9. 用 MD5 算法计算 Tachiyomi source ID
10. 生成 index.min.json + repo.json + 提取 icon
11. 提交到 repo/ 目录并推送
```

### 4.2 仓库分发结构

构建完成后 `repo/` 目录结构：

```
repo/
├── apk/
│   └── tachiyomi-all.komga-gorse-v1.0.apk    # Release 签名的扩展 APK
├── icon/
│   └── eu.kanade.tachiyomi...komgagorse.png  # 扩展图标
├── index.min.json                             # 扩展元数据（Mihon 读取）
└── repo.json                                  # 仓库元数据（含签名指纹）
```

### 4.3 Mihon 添加仓库

在 Mihon 中添加以下 URL：
```
https://raw.githubusercontent.com/maolei1024/komga/master/repo/index.min.json
```

> ⚠️ **重要：** 当前签名密钥每次 CI 构建时自动生成。如需保证更新时不用卸载重装扩展，应将签名密钥存储为 GitHub Secrets（见下方"下一步"章节）。

---

## 5. 已解决的关键问题

| # | 问题 | 原因 | 解决方案 |
|---|------|------|----------|
| 1 | Gorse 只收到 `genres`，缺少 `tags` 和 `authors` | `buildLabelsForSeries` 只读取了 `SeriesMetadata`，未合并 `BookMetadataAggregation` | 合并两个数据源：genres 来自 metadata，authors 来自 aggregation，tags 合并两者 |
| 2 | 阅读完成后 Gorse 未收到 `read` 反馈 | 监听的不是正确的阅读进度事件 | 监听 `ReadProgressChanged` 事件，检查 `completed=true`，通过 Book 找到 seriesId |
| 3 | Mihon 提示"仓库网址无效" | 缺少 `repo.json` 文件 | 添加含 `name`、`website`、`signingKeyFingerprint` 的 `repo.json` |
| 4 | CI 提取包名为 `14`（minSdkVersion） | `sed` 正则在 aapt 多行输出中匹配错误 | 改用 Python `re.search` 可靠解析 |
| 5 | CI 签名指纹为空 | `keytool -printcert -jarfile` 不支持 V2/V3 签名 | 改用 `apksigner verify --print-certs` |
| 6 | `extensions-inspector` 下载失败 | keiyoushi 的 inspector 仓库不公开 | 直接用 MD5 算法计算 source ID |

---

## 6. 下一步开发指南

### 6.1 签名密钥持久化（优先级：高）

当前每次 CI 构建都会生成新的签名密钥，导致用户更新扩展时需要卸载旧版再安装新版。

**解决方案：**
1. 生成一个永久签名密钥：
   ```bash
   keytool -genkeypair -alias komga-gorse -keypass <密码> \
     -keystore komga-gorse.jks -storepass <密码> \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -dname "CN=Komga Gorse"
   ```
2. 将密钥文件 Base64 编码后存为 GitHub Secret：
   ```bash
   base64 < komga-gorse.jks | pbcopy  # 复制到剪贴板
   ```
3. 在 GitHub 仓库 Settings → Secrets 中添加：
   - `SIGNING_KEY` — Base64 编码的 `.jks` 文件内容
   - `ALIAS` — 密钥别名
   - `KEY_STORE_PASSWORD` — keystore 密码
   - `KEY_PASSWORD` — key 密码
4. 修改 CI 工作流中的"Generate signing key"步骤：
   ```yaml
   - name: Prepare signing key
     run: echo ${{ secrets.SIGNING_KEY }} | base64 -d > /tmp/signingkey.jks
   ```

### 6.2 暴露批量同步 API（优先级：高）

当前 `syncAllItems()`、`syncAllUsers()`、`syncAllFeedback()` 没有 REST 端点。建议新增：

```kotlin
@RestController
@RequestMapping("api/v1/gorse")
class GorseSyncController(
    private val gorseEventListener: GorseEventListener,
) {
    @PostMapping("sync/items")
    fun syncItems() = gorseEventListener.syncAllItems()

    @PostMapping("sync/users")
    fun syncUsers() = gorseEventListener.syncAllUsers()

    @PostMapping("sync/feedback")
    fun syncFeedback() = gorseEventListener.syncAllFeedback()

    @PostMapping("sync/all")
    fun syncAll() = mapOf(
        "items" to gorseEventListener.syncAllItems(),
        "users" to gorseEventListener.syncAllUsers(),
        "feedback" to gorseEventListener.syncAllFeedback(),
    )
}
```

### 6.3 Gorse 配置 UI（优先级：中）

在 Komga WebUI 设置页面添加 Gorse 配置表单，调用后端修改 `GorseSettingsProvider` 的值。需要新增 REST 端点：

```kotlin
@GetMapping("api/v1/gorse/settings")  // 获取当前配置
@PutMapping("api/v1/gorse/settings")  // 更新配置
```

### 6.4 用户反馈按钮 — Mihon 改造（优先级：中）

两种实现思路：

**方案 A：修改 Mihon（Fork）**
- 在漫画详情页添加 👍/👎 按钮
- Series ID 可从 `manga.url` 解析：`manga.url.substringAfterLast("/")`
- 调用 Komga 后端（新增端点）或直接调用 Gorse API

**方案 B：后端自动反馈（已实现）**
- 阅读完成自动发送 `read` 反馈（当前方案）
- 可扩展为：加入收藏 = `like`，移出收藏 = 取消 `like`

### 6.5 推荐算法调优（优先级：低）

- 在 Gorse Dashboard 中调整推荐参数
- 考虑增加更多反馈类型（如 `browse`=浏览、`favorite`=收藏）
- 调整 Item Labels 的权重

---

## 7. 环境配置速查

### Gorse 配置
```yaml
GORSE_ENABLED: true
GORSE_API_URL: http://129.153.236.237:8088
GORSE_API_KEY: xuniadmin
GORSE_FEEDBACK_TYPE: read
```

### 扩展仓库 URL
```
https://raw.githubusercontent.com/maolei1024/komga/master/repo/index.min.json
```

### 关键路径
```
komga/src/main/kotlin/.../infrastructure/gorse/  → 后端 Gorse 集成代码
komga/src/main/kotlin/.../interfaces/api/rest/    → 推荐 API 端点
komgagorse-extension/                             → Mihon 扩展源码
repo/                                             → 扩展分发仓库
.github/workflows/build-extension.yml             → CI 构建流程
```
