# Komga-Gorse Mihon 扩展开发者文档

> 本文档为 Mihon 扩展开发者提供 Like/Unlike 反馈功能的集成指南。

## 1. 架构概览

```
┌─────────────┐    HTTP     ┌──────────────────┐   HTTP    ┌─────────┐
│   Mihon     │────────────→│   Komga 后端      │─────────→│  Gorse  │
│  (扩展)     │  匿名 API   │ (Spring Boot)     │   REST    │ (推荐)  │
└─────────────┘             └──────────────────┘           └─────────┘
```

**关键流程**：Mihon 扩展 → Komga 匿名 Like API → Komga 代理转发 → Gorse 反馈 API

---

## 2. 匿名 Like API（无需登录）

**Base URL**: `{komga_address}/api/v1/gorse/like/anonymous`

> ⚠️ 需要在 Komga 管理后台 `/settings/gorse` 中配置"匿名用户 ID"，否则接口返回 400 错误。

### 2.1 查询 Series 是否被喜欢

```
GET /api/v1/gorse/like/anonymous/{seriesId}
```

**响应**：
```json
{ "liked": true }
```

### 2.2 喜欢 Series

```
PUT /api/v1/gorse/like/anonymous/{seriesId}
```

**响应**：
```json
{ "success": true }
```

### 2.3 取消喜欢 Series

```
DELETE /api/v1/gorse/like/anonymous/{seriesId}
```

**响应**：
```json
{ "success": true }
```

### 2.4 通过 BookId 查询所属 Series 是否被喜欢

```
GET /api/v1/gorse/like/anonymous/book/{bookId}
```

**响应**：
```json
{ "liked": true, "seriesId": "0HBSQ1Y39Z9R3" }
```

> 此接口自动将 bookId 解析为所属 seriesId，适用于从章节页面发起 like。

---

## 3. 如何获取 ItemId（seriesId / bookId）

### 3.1 在扩展代码中获取

当前 KomgaGorse 扩展中，**manga 和 chapter 的 URL 已经包含了 ID**：

| 对象 | URL 格式 | 示例 |
|------|----------|------|
| Series (SManga) | `{baseUrl}/api/v1/series/{seriesId}` | `http://localhost:25600/api/v1/series/0HBSQ1Y39Z9R3` |
| Book (SChapter) | `{baseUrl}/api/v1/books/{bookId}` | `http://localhost:25600/api/v1/books/0HBSQ5SZZBR84` |

**提取 seriesId**：
```kotlin
// 从 SManga.url 中提取 seriesId
fun extractSeriesId(manga: SManga): String {
    // URL 格式: {baseUrl}/api/v1/series/{seriesId}
    return manga.url.substringAfterLast("/")
}
```

**提取 bookId**：
```kotlin
// 从 SChapter.url 中提取 bookId
fun extractBookId(chapter: SChapter): String {
    // URL 格式: {baseUrl}/api/v1/books/{bookId}
    return chapter.url.substringAfterLast("/")
}
```

### 3.2 BookDto 中的 seriesId

在 `chapterListParse()` 解析 BookDto 时，可以直接获取 `seriesId`：

```kotlin
@Serializable
class BookDto(
    val id: String,         // bookId
    val seriesId: String,   // 所属 seriesId ← 可以直接用
    val seriesTitle: String,
    // ...
)
```

### 3.3 从面向用户的 URL 转换

Komga WebUI 的 URL 格式：
- 系列页：`/series/{seriesId}`
- 书籍页：`/book/{bookId}`

扩展中的转换（已有逻辑）：
```kotlin
// getMangaUrl: API URL → 用户可见 URL
override fun getMangaUrl(manga: SManga) = manga.url.replace("/api/v1", "")
// 例: {baseUrl}/api/v1/series/ABC → {baseUrl}/series/ABC

// getChapterUrl: API URL → 用户可见 URL
override fun getChapterUrl(chapter: SChapter) = chapter.url.replace("/api/v1/books", "/book")
// 例: {baseUrl}/api/v1/books/XYZ → {baseUrl}/book/XYZ
```

---

## 4. 集成示例代码

### 4.1 在 KomgaGorse 中添加 Like 功能

```kotlin
import okhttp3.Request

// 查询某个 series 的 like 状态
fun isSeriesLiked(seriesId: String): Boolean {
    val request = Request.Builder()
        .url("$baseUrl/api/v1/gorse/like/anonymous/$seriesId")
        .get()
        .build()
    
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: return false
    // 解析 {"liked": true/false}
    return body.contains("\"liked\":true") || body.contains("\"liked\": true")
}

// 喜欢一个 series
fun likeSeries(seriesId: String): Boolean {
    val request = Request.Builder()
        .url("$baseUrl/api/v1/gorse/like/anonymous/$seriesId")
        .put(okhttp3.RequestBody.create(null, byteArrayOf())) // 空 body PUT
        .build()
    
    val response = client.newCall(request).execute()
    return response.isSuccessful
}

// 取消喜欢
fun unlikeSeries(seriesId: String): Boolean {
    val request = Request.Builder()
        .url("$baseUrl/api/v1/gorse/like/anonymous/$seriesId")
        .delete()
        .build()
    
    val response = client.newCall(request).execute()
    return response.isSuccessful
}

// 通过 bookId 查询 like 状态（自动解析到 series）
fun isBookSeriesLiked(bookId: String): Pair<Boolean, String> {
    val request = Request.Builder()
        .url("$baseUrl/api/v1/gorse/like/anonymous/book/$bookId")
        .get()
        .build()
    
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: return Pair(false, "")
    // 解析 {"liked": true, "seriesId": "xxx"}
    val liked = body.contains("\"liked\":true") || body.contains("\"liked\": true")
    val seriesId = Regex("\"seriesId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
    return Pair(liked, seriesId)
}
```

### 4.2 在 Mihon 修改版（fork）中集成

如果直接修改 Mihon 源码，可以在 MangaScreen 或 ChapterScreen 中添加：

```kotlin
// 在 MangaScreen 的 composable 中
// manga.url 包含 "{baseUrl}/api/v1/series/{seriesId}"
val seriesId = manga.url.substringAfterLast("/")

// 使用协程调用
LaunchedEffect(seriesId) { 
    val liked = isSeriesLiked(seriesId)
    // 更新 UI 状态
}
```

---

## 5. Komga 后端配置说明

在 Komga 管理后台 → 设置 → `/settings/gorse` 页面中配置：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 启用 Gorse 集成 | 开关 | 关 |
| Gorse API 地址 | Gorse 服务地址 | `http://localhost:8087` |
| Gorse API 密钥 | Gorse 认证密钥 | 空 |
| 已读反馈类型 | 阅读完成自动发送的反馈类型 | `read` |
| **正反馈类型** | 点击喜欢按钮发送的反馈类型 | `like` |
| **匿名用户 ID** | 匿名接口使用的 userId | 空（必须配置才能使用匿名 API） |

### 匿名用户 ID 说明

- 所有匿名 API 请求共享同一个 userId
- 适用于单用户场景（个人使用的 Komga 实例）
- 建议设置为 Komga 中实际用户的 ID，这样匿名反馈和登录用户反馈一致
- 用户 ID 可以在 Komga 管理后台的用户管理页面找到

---

## 6. 错误处理

| HTTP 状态码 | 含义 | 处理建议 |
|-------------|------|----------|
| 200 | 成功 | 正常解析 JSON |
| 400 | 匿名用户 ID 未配置 | 提示用户到 Komga 设置中配置 |
| 503 | Gorse 未启用 | 提示用户启用 Gorse 集成 |
| 404 | BookId 不存在 | 返回 `liked: false` |

---

## 7. 相关文件参考

### 后端
| 文件 | 说明 |
|------|------|
| `GorseLikeController.kt` | Like API 控制器（认证 + 匿名） |
| `GorseSettingsProvider.kt` | 配置管理 |
| `GorseClient.kt` | Gorse HTTP 客户端 |
| `SecurityConfiguration.kt` | 安全配置（匿名路径 permitAll） |

### 扩展
| 文件 | 说明 |
|------|------|
| `KomgaGorse.kt` | 主扩展类（URL 构建、认证） |
| `Dto.kt` | 数据模型（SeriesDto.id、BookDto.seriesId） |

### Gorse 反馈数据结构

Komga 发送到 Gorse 的 feedback 格式：
```json
{
    "FeedbackType": "like",
    "UserId": "配置的匿名用户ID",
    "ItemId": "seriesId",
    "Timestamp": "2026-03-04T12:00:00Z"
}
```

> ItemId 始终为 **seriesId**（即使通过 book 接口调用，也会自动解析到 series）。
