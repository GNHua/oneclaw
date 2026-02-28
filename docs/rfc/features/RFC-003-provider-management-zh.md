# RFC-003: 模型/提供商管理

## 文档信息
- **RFC编号**: RFC-003
- **关联PRD**: [FEAT-003 (模型/提供商管理)](../../prd/features/FEAT-003-provider-zh.md)
- **关联设计**: [UI设计规范](../../design/ui-design-spec-zh.md) (第3、4、7节)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27（根据第二层测试实现修复更新）
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
模型/提供商管理是 OneClawShadow 所有 AI 交互的基础功能。在用户能发送任何消息之前，必须至少配置一个带有有效 API key 的提供商。本 RFC 详细说明了提供商 CRUD 操作、API key 安全存储、模型列表获取、连接测试、首次设置流程以及全局默认模型选择的技术实现。

本 RFC 仅涵盖**提供商管理基础设施** -- 完整定义了 `ModelApiAdapter` 接口，但仅实现 `listModels()` 和 `testConnection()`。`sendMessageStream()` 的实现推迟到 RFC-001（聊天交互）。

### 目标
1. 实现提供商和模型数据持久化（Room 实体、DAO、仓库）
2. 实现通过 EncryptedSharedPreferences 安全存储 API key
3. 实现 OpenAI、Anthropic 和 Google Gemini 的提供商 API 适配器（模型列表和连接测试）
4. 实现首次启动时预配置提供商的种子数据
5. 实现连接测试，提供清晰的成功/失败反馈
6. 实现动态模型列表获取与预设回退
7. 实现全局默认模型的选择和持久化
8. 实现提供商管理 UI（列表页、详情页、设置页）
9. 提供足够的实现细节以支持 AI 辅助代码生成

### 非目标
- 流式聊天消息发送（`sendMessageStream` 实现）-- 推迟到 RFC-001
- API 调用中的工具定义格式化 -- 推迟到 RFC-004
- SSE 流式传输实现 -- 推迟到 RFC-001
- OAuth 认证
- 单个提供商多 key
- 提供商使用分析
- 提供商配置的数据同步/备份

## 技术方案

### 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                         UI 层                                │
│  SetupScreen  ProviderListScreen  ProviderDetailScreen       │
│       │              │                   │                    │
│       v              v                   v                    │
│  (共享)     ProviderListViewModel  ProviderDetailViewModel   │
├─────────────────────────────────────────────────────────────┤
│                       领域层                                  │
│  TestConnectionUseCase  FetchModelsUseCase                   │
│  SetDefaultModelUseCase  SeedProvidersUseCase                │
│       │                                                      │
│       v                                                      │
│  ProviderRepository (接口)                                    │
├─────────────────────────────────────────────────────────────┤
│                        数据层                                 │
│  ProviderRepositoryImpl                                      │
│       │           │            │                              │
│       v           v            v                              │
│  ProviderDao   ApiKeyStorage  ModelApiAdapterFactory         │
│  ModelDao                      ├── OpenAiAdapter             │
│                                ├── AnthropicAdapter          │
│                                └── GeminiAdapter             │
└─────────────────────────────────────────────────────────────┘
```

### 核心组件

1. **ApiKeyStorage**
   - 职责：安全地读取/写入/删除 API key
   - 底层：EncryptedSharedPreferences（Android Keystore）
   - 接口：`getApiKey(providerId)`、`setApiKey(providerId, key)`、`deleteApiKey(providerId)`、`hasApiKey(providerId)`

2. **ProviderRepositoryImpl**
   - 职责：协调提供商 CRUD、模型操作，委托给 DAO 和适配器
   - 依赖：ProviderDao、ModelDao、ApiKeyStorage、ModelApiAdapterFactory、SettingsDao

3. **ModelApiAdapter（接口）+ 实现**
   - 职责：抽象不同提供商的 API 格式差异
   - 实现：OpenAiAdapter、AnthropicAdapter、GeminiAdapter
   - 本 RFC 实现：`listModels()`、`testConnection()`
   - 推迟到 RFC-001：`sendMessageStream()`

4. **预配置提供商种子数据**
   - 职责：在首次创建数据库时插入 3 个内置提供商模板
   - 机制：Room `RoomDatabase.Callback.onCreate`

5. **用例**
   - `TestConnectionUseCase`：验证 API key + 端点可达性
   - `FetchModelsUseCase`：从提供商 API 获取模型列表，失败时使用预设回退
   - `SetDefaultModelUseCase`：设置全局默认模型/提供商
   - `SeedProvidersUseCase`：非传统用例 -- 由数据库回调处理（见下文）

## 数据模型

### 领域模型

这些模型定义在 Core 模块（`core/model/`）。`Provider` 和 `AiModel` 模型已在 RFC-000 中定义。本节记录**更新后**的定义。

#### Provider（已更新）

```kotlin
data class Provider(
    val id: String,                    // UUID
    val name: String,                  // 显示名称（如 "OpenAI"、"我的本地服务器"）
    val type: ProviderType,            // API 协议格式：OPENAI、ANTHROPIC、GEMINI
    val apiBaseUrl: String,            // API 请求的基础 URL
    val isPreConfigured: Boolean,      // true = 内置模板，false = 用户创建
    val isActive: Boolean,             // 是否启用该提供商
    val createdAt: Long,               // 时间戳（毫秒）
    val updatedAt: Long                // 时间戳（毫秒）
)

enum class ProviderType {
    OPENAI,     // OpenAI 兼容 API 格式（也用于兼容 OpenAI 的自定义端点）
    ANTHROPIC,  // Anthropic API 格式
    GEMINI      // Google Gemini API 格式
}
// 注意：没有 CUSTOM 类型。自定义端点根据兼容的 API 协议选择 OPENAI、ANTHROPIC 或 GEMINI。
// `isPreConfigured` 字段区分内置模板和用户创建的提供商。
```

**与 RFC-000 的变更**：移除了 `ProviderType.CUSTOM`。`type` 字段现在表示 API 协议格式，而非服务身份。用户创建的提供商根据端点兼容的 API 格式选择 `OPENAI`、`ANTHROPIC` 或 `GEMINI`。`isPreConfigured` 区分内置和用户创建。

#### AiModel（无变更）

```kotlin
data class AiModel(
    val id: String,                    // 模型标识符（如 "gpt-4o"）
    val displayName: String?,          // 人类友好的名称（如 "GPT-4o"）
    val providerId: String,            // 所属提供商
    val isDefault: Boolean,            // 是否为全局默认
    val source: ModelSource            // 添加方式
)

enum class ModelSource {
    DYNAMIC,   // 从提供商 API 获取
    PRESET,    // 预配置回退
    MANUAL     // 用户手动添加
}
```

#### ConnectionTestResult（新增）

```kotlin
data class ConnectionTestResult(
    val success: Boolean,
    val modelCount: Int?,              // 找到的模型数量（成功时）
    val errorType: ConnectionErrorType?,
    val errorMessage: String?
)

enum class ConnectionErrorType {
    AUTH_FAILURE,       // 401/403 -- 无效的 API key
    NETWORK_FAILURE,    // 无法连接到服务器
    TIMEOUT,            // 请求超时
    UNKNOWN             // 其他错误
}
```

### Room 实体

#### ProviderEntity

```kotlin
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "type")
    val type: String,                  // "OPENAI"、"ANTHROPIC"、"GEMINI"
    @ColumnInfo(name = "api_base_url")
    val apiBaseUrl: String,
    @ColumnInfo(name = "is_pre_configured")
    val isPreConfigured: Boolean,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

#### ModelEntity

```kotlin
@Entity(
    tableName = "models",
    primaryKeys = ["id", "provider_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("provider_id")]
)
data class ModelEntity(
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,
    val source: String                 // "DYNAMIC"、"PRESET"、"MANUAL"
)
```

### 数据库 Schema

```sql
CREATE TABLE providers (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,               -- "OPENAI"、"ANTHROPIC"、"GEMINI"
    api_base_url TEXT NOT NULL,
    is_pre_configured INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE models (
    id TEXT NOT NULL,
    display_name TEXT,
    provider_id TEXT NOT NULL,
    is_default INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,             -- "DYNAMIC"、"PRESET"、"MANUAL"
    PRIMARY KEY (id, provider_id),
    FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);

CREATE INDEX idx_models_provider_id ON models(provider_id);
```

### 实体-领域映射器

```kotlin
// ProviderMapper.kt
fun ProviderEntity.toDomain(): Provider = Provider(
    id = id,
    name = name,
    type = ProviderType.valueOf(type),
    apiBaseUrl = apiBaseUrl,
    isPreConfigured = isPreConfigured,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Provider.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    type = type.name,
    apiBaseUrl = apiBaseUrl,
    isPreConfigured = isPreConfigured,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// ModelMapper.kt
fun ModelEntity.toDomain(): AiModel = AiModel(
    id = id,
    displayName = displayName,
    providerId = providerId,
    isDefault = isDefault,
    source = ModelSource.valueOf(source)
)

fun AiModel.toEntity(): ModelEntity = ModelEntity(
    id = id,
    displayName = displayName,
    providerId = providerId,
    isDefault = isDefault,
    source = source.name
)
```

## API Key 存储

### ApiKeyStorage 类

API key 存储在 EncryptedSharedPreferences 中，完全独立于 Room 数据库。该类位于数据层 `data/security/ApiKeyStorage.kt`。

```kotlin
class ApiKeyStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "oneclaw_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // DEBUG 构建支持纯文本 fallback 偏好文件，使仪器化测试可以注入 API key，
    // 而无需跨进程访问 EncryptedSharedPreferences。
    // fallback 文件 "oneclaw_api_keys_debug" 由测试中的 SetupDataInjector 写入。
    private val debugPrefs: SharedPreferences? = if (BuildConfig.DEBUG) {
        context.getSharedPreferences("oneclaw_api_keys_debug", Context.MODE_PRIVATE)
    } else null

    fun getApiKey(providerId: String): String? {
        // 在 DEBUG 构建中，纯文本 fallback 优先（由仪器化测试设置）
        val debugKey = debugPrefs?.getString("api_key_$providerId", null)
        if (debugKey != null) return debugKey
        return prefs.getString("api_key_$providerId", null)
    }

    fun setApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString("api_key_$providerId", apiKey.trim()).apply()
    }

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove("api_key_$providerId").apply()
        debugPrefs?.edit()?.remove("api_key_$providerId")?.apply()
    }

    fun hasApiKey(providerId: String): Boolean {
        if (debugPrefs?.contains("api_key_$providerId") == true) return true
        return prefs.contains("api_key_$providerId")
    }
}
```

### 关键设计决策
- **Key 格式**：`api_key_{providerId}` —— 简单，由于 providerId 是固定字符串（如 `provider-anthropic`）所以无冲突
- **保存时去空格**：`apiKey.trim()` 去除用户粘贴 key 时常见的前后空白字符
- **EncryptedSharedPreferences**：使用 AES256-GCM 加密值，AES256-SIV 加密键名，通过 `MasterKey.Builder` 由 Android Keystore 主密钥支持
- **不缓存**：每次直接从 EncryptedSharedPreferences 读取。底层实现有自己的缓存，且 API key 读取频率不高，不影响性能
- **Debug fallback**：在 `BuildConfig.DEBUG` 构建中，会先检查纯文本 `SharedPreferences` 文件（`oneclaw_api_keys_debug`）。这使仪器化测试无需跨进程操作 `EncryptedSharedPreferences` 就能注入 API key。在 release 构建中 fallback 不生效。
- **需要 `buildConfig = true`**：只有在 `app/build.gradle.kts` 中设置 `buildFeatures { buildConfig = true }` 时，`BuildConfig.DEBUG` 才会被生成。

## DAO 接口

### ProviderDao

```kotlin
@Dao
interface ProviderDao {

    @Query("SELECT * FROM providers ORDER BY is_pre_configured DESC, created_at ASC")
    fun getAllProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProviderById(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE is_pre_configured = 1")
    suspend fun getPreConfiguredProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE is_active = 1")
    fun getActiveProviders(): Flow<List<ProviderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProviders(providers: List<ProviderEntity>)

    @Update
    suspend fun updateProvider(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id AND is_pre_configured = 0")
    suspend fun deleteCustomProvider(id: String): Int

    @Query("UPDATE providers SET is_active = :isActive, updated_at = :updatedAt WHERE id = :id")
    suspend fun setProviderActive(id: String, isActive: Boolean, updatedAt: Long)
}
```

### ModelDao

```kotlin
@Dao
interface ModelDao {

    @Query("SELECT * FROM models WHERE provider_id = :providerId")
    suspend fun getModelsForProvider(providerId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE is_default = 1 LIMIT 1")
    fun getDefaultModel(): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE id = :modelId AND provider_id = :providerId")
    suspend fun getModel(modelId: String, providerId: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: ModelEntity)

    @Query("DELETE FROM models WHERE provider_id = :providerId AND source = :source")
    suspend fun deleteModelsBySource(providerId: String, source: String)

    @Query("DELETE FROM models WHERE provider_id = :providerId")
    suspend fun deleteAllModelsForProvider(providerId: String)

    @Query("UPDATE models SET is_default = 0 WHERE is_default = 1")
    suspend fun clearDefaultModel()

    @Query("UPDATE models SET is_default = 1 WHERE id = :modelId AND provider_id = :providerId")
    suspend fun setDefaultModel(modelId: String, providerId: String)

    @Transaction
    suspend fun updateDefaultModel(modelId: String, providerId: String) {
        clearDefaultModel()
        setDefaultModel(modelId, providerId)
    }
}
```

## 提供商 API 适配器

### ModelApiAdapter 接口

这是完整接口。`sendMessageStream()` 在此声明，但在 RFC-001 中实现。

```kotlin
interface ModelApiAdapter {

    /**
     * 从提供商 API 获取可用模型列表。
     *
     * @param apiBaseUrl 提供商 API 的基础 URL（如 "https://api.openai.com/v1"）
     * @param apiKey 用户该提供商的 API key
     * @return 成功时返回可用模型列表，否则返回错误
     */
    suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>>

    /**
     * 测试与提供商的连接。通常内部调用 listModels。
     *
     * @return ConnectionTestResult 包含成功/失败详情
     */
    suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult>

    /**
     * 发送流式聊天补全请求。
     * 实现推迟到 RFC-001。
     */
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent>
}
```

### ModelApiAdapterFactory

```kotlin
class ModelApiAdapterFactory(private val okHttpClient: OkHttpClient) {

    fun getAdapter(providerType: ProviderType): ModelApiAdapter {
        return when (providerType) {
            ProviderType.OPENAI -> OpenAiAdapter(okHttpClient)
            ProviderType.ANTHROPIC -> AnthropicAdapter(okHttpClient)
            ProviderType.GEMINI -> GeminiAdapter(okHttpClient)
        }
    }
}
```

### OpenAI 适配器

#### 模型列表 API

```
GET {apiBaseUrl}/models
Headers:
  Authorization: Bearer {apiKey}

Response 200:
{
  "data": [
    {
      "id": "gpt-4o",
      "object": "model",
      "owned_by": "openai"
    },
    ...
  ]
}
```

#### DTO

```kotlin
// data/remote/dto/openai/

@Serializable
data class OpenAiModelListResponse(
    val data: List<OpenAiModelDto>
)

@Serializable
data class OpenAiModelDto(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null
)
```

#### 实现

```kotlin
class OpenAiAdapter(private val client: OkHttpClient) : ModelApiAdapter {

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(
                            message = "Empty response body",
                            code = ErrorCode.PROVIDER_ERROR
                        )
                    val parsed = Json.decodeFromString<OpenAiModelListResponse>(body)
                    val models = parsed.data
                        .filter { isRelevantOpenAiModel(it.id) }
                        .map { dto ->
                            AiModel(
                                id = dto.id,
                                displayName = formatOpenAiModelName(dto.id),
                                providerId = "",  // 由调用方设置
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> {
                    AppResult.Error(
                        message = "认证失败。请检查您的 API key。",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API 错误: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(
                message = "无法连接到服务器。请检查 URL 和网络连接。",
                code = ErrorCode.NETWORK_ERROR,
                exception = e
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(
                message = "连接超时。",
                code = ErrorCode.TIMEOUT_ERROR,
                exception = e
            )
        } catch (e: Exception) {
            AppResult.Error(
                message = "意外错误: ${e.message}",
                code = ErrorCode.UNKNOWN,
                exception = e
            )
        }
    }

    override suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult> {
        return when (val result = listModels(apiBaseUrl, apiKey)) {
            is AppResult.Success -> {
                AppResult.Success(
                    ConnectionTestResult(
                        success = true,
                        modelCount = result.data.size,
                        errorType = null,
                        errorMessage = null
                    )
                )
            }
            is AppResult.Error -> {
                val errorType = when (result.code) {
                    ErrorCode.AUTH_ERROR -> ConnectionErrorType.AUTH_FAILURE
                    ErrorCode.NETWORK_ERROR -> ConnectionErrorType.NETWORK_FAILURE
                    ErrorCode.TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
                    else -> ConnectionErrorType.UNKNOWN
                }
                AppResult.Success(
                    ConnectionTestResult(
                        success = false,
                        modelCount = null,
                        errorType = errorType,
                        errorMessage = result.message
                    )
                )
            }
        }
    }

    override fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent> {
        // 实现推迟到 RFC-001
        throw NotImplementedError("sendMessageStream 在 RFC-001 中实现")
    }

    /**
     * 过滤掉非聊天模型（embeddings、whisper、tts、dall-e 等）。
     * 只保留可用于聊天补全的模型。
     */
    private fun isRelevantOpenAiModel(modelId: String): Boolean {
        val chatPrefixes = listOf("gpt-", "o1", "o3", "o4", "chatgpt-")
        return chatPrefixes.any { modelId.startsWith(it) }
    }

    /**
     * 将模型 ID 格式化为人类友好的显示名称。
     * 例如："gpt-4o" -> "GPT-4o"，"gpt-4o-mini" -> "GPT-4o Mini"
     */
    private fun formatOpenAiModelName(modelId: String): String {
        return modelId
            .replace("gpt-", "GPT-")
            .replace("-mini", " Mini")
    }
}
```

### Anthropic 适配器

#### 模型列表 API

```
GET {apiBaseUrl}/models
Headers:
  x-api-key: {apiKey}
  anthropic-version: 2023-06-01

Response 200:
{
  "data": [
    {
      "id": "claude-sonnet-4-20250514",
      "display_name": "Claude Sonnet 4",
      "type": "model"
    },
    ...
  ]
}
```

#### DTO

```kotlin
// data/remote/dto/anthropic/

@Serializable
data class AnthropicModelListResponse(
    val data: List<AnthropicModelDto>
)

@Serializable
data class AnthropicModelDto(
    val id: String,
    @SerialName("display_name")
    val displayName: String? = null,
    val type: String? = null
)
```

#### 实现

```kotlin
class AnthropicAdapter(private val client: OkHttpClient) : ModelApiAdapter {

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/models")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(
                            message = "空响应体",
                            code = ErrorCode.PROVIDER_ERROR
                        )
                    val parsed = Json.decodeFromString<AnthropicModelListResponse>(body)
                    val models = parsed.data
                        .filter { it.type == "model" }
                        .map { dto ->
                            AiModel(
                                id = dto.id,
                                displayName = dto.displayName,
                                providerId = "",  // 由调用方设置
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> {
                    AppResult.Error(
                        message = "认证失败。请检查您的 API key。",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API 错误: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "无法连接到服务器。", code = ErrorCode.NETWORK_ERROR, exception = e)
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(message = "连接超时。", code = ErrorCode.TIMEOUT_ERROR, exception = e)
        } catch (e: Exception) {
            AppResult.Error(message = "意外错误: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    override suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult> {
        return when (val result = listModels(apiBaseUrl, apiKey)) {
            is AppResult.Success -> AppResult.Success(
                ConnectionTestResult(success = true, modelCount = result.data.size, errorType = null, errorMessage = null)
            )
            is AppResult.Error -> {
                val errorType = when (result.code) {
                    ErrorCode.AUTH_ERROR -> ConnectionErrorType.AUTH_FAILURE
                    ErrorCode.NETWORK_ERROR -> ConnectionErrorType.NETWORK_FAILURE
                    ErrorCode.TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
                    else -> ConnectionErrorType.UNKNOWN
                }
                AppResult.Success(
                    ConnectionTestResult(success = false, modelCount = null, errorType = errorType, errorMessage = result.message)
                )
            }
        }
    }

    override fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent> {
        throw NotImplementedError("sendMessageStream 在 RFC-001 中实现")
    }
}
```

### Google Gemini 适配器

#### 模型列表 API

```
GET {apiBaseUrl}/models?key={apiKey}

Response 200:
{
  "models": [
    {
      "name": "models/gemini-2.0-flash",
      "displayName": "Gemini 2.0 Flash",
      "supportedGenerationMethods": ["generateContent", "countTokens"]
    },
    ...
  ]
}
```

注意：Gemini 使用查询参数传递 API key，而非请求头。

#### DTO

```kotlin
// data/remote/dto/gemini/

@Serializable
data class GeminiModelListResponse(
    val models: List<GeminiModelDto>
)

@Serializable
data class GeminiModelDto(
    val name: String,                  // 如 "models/gemini-2.0-flash"
    val displayName: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)
```

#### 实现

```kotlin
class GeminiAdapter(private val client: OkHttpClient) : ModelApiAdapter {

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val url = "${apiBaseUrl.trimEnd('/')}/models?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(
                            message = "空响应体",
                            code = ErrorCode.PROVIDER_ERROR
                        )
                    val parsed = Json.decodeFromString<GeminiModelListResponse>(body)
                    val models = parsed.models
                        .filter { "generateContent" in it.supportedGenerationMethods }
                        .map { dto ->
                            val modelId = dto.name.removePrefix("models/")
                            AiModel(
                                id = modelId,
                                displayName = dto.displayName,
                                providerId = "",  // 由调用方设置
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 || response.code == 400 -> {
                    // Gemini 在某些情况下对无效 API key 返回 400
                    AppResult.Error(
                        message = "认证失败。请检查您的 API key。",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API 错误: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "无法连接到服务器。", code = ErrorCode.NETWORK_ERROR, exception = e)
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(message = "连接超时。", code = ErrorCode.TIMEOUT_ERROR, exception = e)
        } catch (e: Exception) {
            AppResult.Error(message = "意外错误: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    override suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult> {
        return when (val result = listModels(apiBaseUrl, apiKey)) {
            is AppResult.Success -> AppResult.Success(
                ConnectionTestResult(success = true, modelCount = result.data.size, errorType = null, errorMessage = null)
            )
            is AppResult.Error -> {
                val errorType = when (result.code) {
                    ErrorCode.AUTH_ERROR -> ConnectionErrorType.AUTH_FAILURE
                    ErrorCode.NETWORK_ERROR -> ConnectionErrorType.NETWORK_FAILURE
                    ErrorCode.TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
                    else -> ConnectionErrorType.UNKNOWN
                }
                AppResult.Success(
                    ConnectionTestResult(success = false, modelCount = null, errorType = errorType, errorMessage = result.message)
                )
            }
        }
    }

    override fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent> {
        throw NotImplementedError("sendMessageStream 在 RFC-001 中实现")
    }
}
```

## 预配置提供商种子数据

### 策略：Room 数据库回调

三个内置提供商模板（OpenAI、Anthropic、Google Gemini）在数据库首次创建时通过 `RoomDatabase.Callback.onCreate` 插入。该回调仅在数据库文件不存在时执行一次。

### 实现

```kotlin
class AppDatabaseCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val now = System.currentTimeMillis()

        // 预配置提供商模板
        // 插入时不包含 API key -- key 由 ApiKeyStorage 管理
        val providers = listOf(
            // OpenAI
            """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
               VALUES ('provider-openai', 'OpenAI', 'OPENAI', 'https://api.openai.com/v1', 1, 1, $now, $now)""",

            // Anthropic
            """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
               VALUES ('provider-anthropic', 'Anthropic', 'ANTHROPIC', 'https://api.anthropic.com/v1', 1, 1, $now, $now)""",

            // Google Gemini
            """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
               VALUES ('provider-gemini', 'Google Gemini', 'GEMINI', 'https://generativelanguage.googleapis.com/v1beta', 1, 1, $now, $now)"""
        )

        // 预设回退模型
        val models = listOf(
            // OpenAI 预设模型
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gpt-4o', 'GPT-4o', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gpt-4o-mini', 'GPT-4o Mini', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('o1', 'o1', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('o3-mini', 'o3 Mini', 'provider-openai', 0, 'PRESET')""",

            // Anthropic 预设模型
            // 注意：使用 Anthropic API 中实际存在的带版本号的模型 ID。
            // 这些 ID 在第二层测试发现旧 ID 不存在后已更正。
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('claude-opus-4-5-20251101', 'Claude Opus 4.5', 'provider-anthropic', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('claude-sonnet-4-5-20250929', 'Claude Sonnet 4.5', 'provider-anthropic', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('claude-haiku-4-5-20251001', 'Claude Haiku 4.5', 'provider-anthropic', 0, 'PRESET')""",

            // Gemini 预设模型
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gemini-2.0-flash', 'Gemini 2.0 Flash', 'provider-gemini', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gemini-2.5-pro', 'Gemini 2.5 Pro', 'provider-gemini', 0, 'PRESET')"""
        )

        providers.forEach { db.execSQL(it) }
        models.forEach { db.execSQL(it) }
    }
}
```

### 设计决策

- **固定 ID**：预配置提供商使用确定性 ID（`provider-openai`、`provider-anthropic`、`provider-gemini`）而非随机 UUID。便于代码引用，避免种子逻辑重复执行时产生重复数据。
- **种子数据不含 API key**：提供商以"未配置"状态创建。用户必须添加 API key 才能使用。
- **预设模型是回退**：当用户添加 API key 且动态模型列表获取成功时，动态模型会与预设模型并存（不替换）。UI 可以优先显示动态模型。
- **所有提供商默认启用**：预配置提供商默认处于活跃状态。没有 API key 的提供商在 UI 中显示为"未配置"，但技术上仍然是活跃的。

## 仓库实现

### ProviderRepository 接口

定义在 Core 模块。相比 RFC-000 新增了 `addManualModel` 和 `deleteManualModel`。

```kotlin
interface ProviderRepository {
    fun getAllProviders(): Flow<List<Provider>>
    fun getActiveProviders(): Flow<List<Provider>>
    suspend fun getProviderById(id: String): Provider?
    suspend fun createProvider(provider: Provider)
    suspend fun updateProvider(provider: Provider)
    suspend fun deleteProvider(id: String): AppResult<Unit>
    suspend fun setProviderActive(id: String, isActive: Boolean)

    suspend fun getModelsForProvider(providerId: String): List<AiModel>
    suspend fun fetchModelsFromApi(providerId: String): AppResult<List<AiModel>>
    suspend fun addManualModel(providerId: String, modelId: String, displayName: String?): AppResult<Unit>
    suspend fun deleteManualModel(providerId: String, modelId: String): AppResult<Unit>

    suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult>

    fun getGlobalDefaultModel(): Flow<AiModel?>
    suspend fun setGlobalDefaultModel(modelId: String, providerId: String)
}
```

### ProviderRepositoryImpl

```kotlin
class ProviderRepositoryImpl(
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) : ProviderRepository {

    override fun getAllProviders(): Flow<List<Provider>> {
        return providerDao.getAllProviders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getActiveProviders(): Flow<List<Provider>> {
        return providerDao.getActiveProviders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getProviderById(id: String): Provider? {
        return providerDao.getProviderById(id)?.toDomain()
    }

    override suspend fun createProvider(provider: Provider) {
        providerDao.insertProvider(provider.toEntity())
    }

    override suspend fun updateProvider(provider: Provider) {
        providerDao.updateProvider(provider.toEntity())
    }

    override suspend fun deleteProvider(id: String): AppResult<Unit> {
        // 检查是否为全局默认提供商
        val defaultModel = modelDao.getDefaultModel().first()
        if (defaultModel != null && defaultModel.providerId == id) {
            return AppResult.Error(
                message = "无法删除拥有全局默认模型的提供商。请先设置其他默认模型。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        // 删除 API key
        apiKeyStorage.deleteApiKey(id)

        // 从数据库删除（CASCADE 会删除关联的模型）
        val deleted = providerDao.deleteCustomProvider(id)
        return if (deleted > 0) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(
                message = "无法删除预配置提供商。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
    }

    override suspend fun setProviderActive(id: String, isActive: Boolean) {
        providerDao.setProviderActive(id, isActive, System.currentTimeMillis())
    }

    override suspend fun getModelsForProvider(providerId: String): List<AiModel> {
        return modelDao.getModelsForProvider(providerId).map { it.toDomain() }
    }

    override suspend fun fetchModelsFromApi(providerId: String): AppResult<List<AiModel>> {
        val provider = providerDao.getProviderById(providerId)?.toDomain()
            ?: return AppResult.Error(message = "提供商未找到", code = ErrorCode.VALIDATION_ERROR)

        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "该提供商未配置 API key", code = ErrorCode.VALIDATION_ERROR)

        val adapter = adapterFactory.getAdapter(provider.type)
        val result = adapter.listModels(provider.apiBaseUrl, apiKey)

        return when (result) {
            is AppResult.Success -> {
                val modelsWithProvider = result.data.map { it.copy(providerId = providerId) }

                // 替换现有动态模型，保留预设和手动模型
                modelDao.deleteModelsBySource(providerId, ModelSource.DYNAMIC.name)
                modelDao.insertModels(modelsWithProvider.map { it.toEntity() })

                AppResult.Success(modelsWithProvider)
            }
            is AppResult.Error -> result
        }
    }

    override suspend fun addManualModel(
        providerId: String,
        modelId: String,
        displayName: String?
    ): AppResult<Unit> {
        val existing = modelDao.getModel(modelId, providerId)
        if (existing != null) {
            return AppResult.Error(
                message = "模型 '$modelId' 已存在于该提供商。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        val model = AiModel(
            id = modelId,
            displayName = displayName,
            providerId = providerId,
            isDefault = false,
            source = ModelSource.MANUAL
        )
        modelDao.insertModel(model.toEntity())
        return AppResult.Success(Unit)
    }

    override suspend fun deleteManualModel(
        providerId: String,
        modelId: String
    ): AppResult<Unit> {
        val model = modelDao.getModel(modelId, providerId)
        if (model == null) {
            return AppResult.Error(message = "模型未找到", code = ErrorCode.VALIDATION_ERROR)
        }
        if (model.source != ModelSource.MANUAL.name) {
            return AppResult.Error(
                message = "只有手动添加的模型可以删除。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (model.isDefault) {
            return AppResult.Error(
                message = "无法删除全局默认模型。请先更改默认设置。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        modelDao.deleteModelsBySource(providerId, ModelSource.MANUAL.name)
        return AppResult.Success(Unit)
    }

    override suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult> {
        val provider = providerDao.getProviderById(providerId)?.toDomain()
            ?: return AppResult.Error(message = "提供商未找到", code = ErrorCode.VALIDATION_ERROR)

        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "未配置 API key", code = ErrorCode.VALIDATION_ERROR)

        val adapter = adapterFactory.getAdapter(provider.type)
        return adapter.testConnection(provider.apiBaseUrl, apiKey)
    }

    override fun getGlobalDefaultModel(): Flow<AiModel?> {
        return modelDao.getDefaultModel().map { it?.toDomain() }
    }

    override suspend fun setGlobalDefaultModel(modelId: String, providerId: String) {
        modelDao.updateDefaultModel(modelId, providerId)
    }
}
```

## 用例

### TestConnectionUseCase

```kotlin
class TestConnectionUseCase(
    private val providerRepository: ProviderRepository
) {
    suspend operator fun invoke(providerId: String): AppResult<ConnectionTestResult> {
        return providerRepository.testConnection(providerId)
    }
}
```

### FetchModelsUseCase

```kotlin
class FetchModelsUseCase(
    private val providerRepository: ProviderRepository
) {
    /**
     * 从提供商 API 获取模型。如果获取失败且提供商没有现有模型，
     * 预设模型已在种子数据中存入数据库。
     * 无论获取是否成功，都返回当前数据库中的模型列表。
     */
    suspend operator fun invoke(providerId: String): AppResult<List<AiModel>> {
        // 尝试动态获取
        val fetchResult = providerRepository.fetchModelsFromApi(providerId)

        // 无论获取成功与否，返回当前数据库中的模型列表
        val currentModels = providerRepository.getModelsForProvider(providerId)

        return when (fetchResult) {
            is AppResult.Success -> AppResult.Success(currentModels)
            is AppResult.Error -> {
                if (currentModels.isNotEmpty()) {
                    // 获取失败，但有现有模型（预设或之前获取的）
                    AppResult.Success(currentModels)
                } else {
                    // 完全没有模型 -- 传递错误
                    fetchResult
                }
            }
        }
    }
}
```

### SetDefaultModelUseCase

```kotlin
class SetDefaultModelUseCase(
    private val providerRepository: ProviderRepository
) {
    suspend operator fun invoke(modelId: String, providerId: String): AppResult<Unit> {
        // 验证模型存在
        val models = providerRepository.getModelsForProvider(providerId)
        val model = models.find { it.id == modelId }
            ?: return AppResult.Error(
                message = "模型未找到",
                code = ErrorCode.VALIDATION_ERROR
            )

        // 验证提供商是活跃的
        val provider = providerRepository.getProviderById(providerId)
            ?: return AppResult.Error(
                message = "提供商未找到",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (!provider.isActive) {
            return AppResult.Error(
                message = "无法将非活跃提供商的模型设为默认。",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        providerRepository.setGlobalDefaultModel(modelId, providerId)
        return AppResult.Success(Unit)
    }
}
```

## UI 层

### 导航路由

```kotlin
// 在 Routes.kt 中（提供商功能的补充）
object ProviderList : Route("providers")
object ProviderDetail : Route("providers/{providerId}") {
    fun create(providerId: String) = "providers/$providerId"
}
object Setup : Route("setup")
```

### UI 状态定义

#### ProviderListUiState

```kotlin
data class ProviderListUiState(
    val providers: List<ProviderListItem> = emptyList(),
    val isLoading: Boolean = true
)

data class ProviderListItem(
    val id: String,
    val name: String,
    val type: ProviderType,
    val modelCount: Int,
    val isActive: Boolean,
    val isPreConfigured: Boolean,
    val hasApiKey: Boolean,           // 是否已配置 API key
    val connectionStatus: ConnectionStatus
)

enum class ConnectionStatus {
    CONNECTED,       // API key 已配置且最近测试成功
    NOT_CONFIGURED,  // 未设置 API key
    DISCONNECTED     // API key 已设置但最近测试失败（或从未测试）
}
```

#### ProviderDetailUiState

```kotlin
data class ProviderDetailUiState(
    val provider: Provider? = null,
    val models: List<AiModel> = emptyList(),
    val globalDefaultModelId: String? = null,
    val globalDefaultProviderId: String? = null,

    // API Key
    val apiKeyMasked: String = "",     // 如 "sk-...abc1234"
    val apiKeyVisible: Boolean = false,
    val apiKeyFull: String = "",       // 完整 key（仅在可见时加载）
    val isEditingApiKey: Boolean = false,
    val apiKeyInput: String = "",      // 编辑时的输入框值

    // 连接测试
    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,

    // 模型列表
    val isRefreshingModels: Boolean = false,

    // 提供商状态
    val isActive: Boolean = true,
    val isPreConfigured: Boolean = false,

    // 手动添加模型
    val showAddModelDialog: Boolean = false,
    val manualModelIdInput: String = "",

    // 通用
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null
)
```

#### SetupUiState

```kotlin
data class SetupUiState(
    val step: SetupStep = SetupStep.CHOOSE_PROVIDER,
    val selectedProviderType: ProviderType? = null,
    val selectedProviderId: String? = null,
    val apiKeyInput: String = "",
    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,
    val models: List<AiModel> = emptyList(),
    val selectedDefaultModelId: String? = null,
    val errorMessage: String? = null
)

enum class SetupStep {
    CHOOSE_PROVIDER,    // 第1步：选择 OpenAI / Anthropic / Gemini / 自定义
    ENTER_API_KEY,      // 第2步：输入 API key 并测试连接
    SELECT_MODEL        // 第3步：选择默认模型
}
```

### ViewModel

#### ProviderListViewModel

```kotlin
class ProviderListViewModel(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderListUiState())
    val uiState: StateFlow<ProviderListUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            providerRepository.getAllProviders().collect { providers ->
                val items = providers.map { provider ->
                    val hasKey = apiKeyStorage.hasApiKey(provider.id)
                    val models = providerRepository.getModelsForProvider(provider.id)
                    ProviderListItem(
                        id = provider.id,
                        name = provider.name,
                        type = provider.type,
                        modelCount = models.size,
                        isActive = provider.isActive,
                        isPreConfigured = provider.isPreConfigured,
                        hasApiKey = hasKey,
                        connectionStatus = if (!hasKey) ConnectionStatus.NOT_CONFIGURED
                                          else ConnectionStatus.DISCONNECTED
                        // 注意：列表加载时不自动测试连接。
                        // 用户进入详情页时可以更新连接状态。
                    )
                }
                _uiState.update { it.copy(providers = items, isLoading = false) }
            }
        }
    }
}
```

#### ProviderDetailViewModel

```kotlin
class ProviderDetailViewModel(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val fetchModelsUseCase: FetchModelsUseCase,
    private val setDefaultModelUseCase: SetDefaultModelUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val providerId: String = savedStateHandle["providerId"]
        ?: throw IllegalArgumentException("providerId is required")

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    init {
        loadProvider()
        observeDefaultModel()
    }

    private fun loadProvider() {
        viewModelScope.launch {
            val provider = providerRepository.getProviderById(providerId)
                ?: return@launch
            val models = providerRepository.getModelsForProvider(providerId)
            val apiKey = apiKeyStorage.getApiKey(providerId)
            val masked = maskApiKey(apiKey)

            _uiState.update {
                it.copy(
                    provider = provider,
                    models = models,
                    apiKeyMasked = masked,
                    apiKeyFull = apiKey ?: "",
                    isActive = provider.isActive,
                    isPreConfigured = provider.isPreConfigured,
                    isLoading = false
                )
            }
        }
    }

    private fun observeDefaultModel() {
        viewModelScope.launch {
            providerRepository.getGlobalDefaultModel().collect { defaultModel ->
                _uiState.update {
                    it.copy(
                        globalDefaultModelId = defaultModel?.id,
                        globalDefaultProviderId = defaultModel?.providerId
                    )
                }
            }
        }
    }

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            apiKeyStorage.setApiKey(providerId, apiKey)
            _uiState.update {
                it.copy(
                    apiKeyMasked = maskApiKey(apiKey),
                    apiKeyFull = apiKey,
                    isEditingApiKey = false,
                    apiKeyInput = "",
                    successMessage = "API key 已保存。"
                )
            }
        }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, connectionTestResult = null) }

            when (val result = testConnectionUseCase(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = result.data
                        )
                    }
                    // 如果连接成功，同时获取模型
                    if (result.data.success) {
                        refreshModels()
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingModels = true) }

            when (val result = fetchModelsUseCase(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            models = result.data,
                            isRefreshingModels = false
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRefreshingModels = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            when (val result = setDefaultModelUseCase(modelId, providerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(successMessage = "已设置默认模型。") }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun toggleProviderActive() {
        viewModelScope.launch {
            val newActive = !(_uiState.value.isActive)
            providerRepository.setProviderActive(providerId, newActive)
            _uiState.update { it.copy(isActive = newActive) }
        }
    }

    fun addManualModel(modelId: String, displayName: String?) {
        viewModelScope.launch {
            when (val result = providerRepository.addManualModel(providerId, modelId, displayName)) {
                is AppResult.Success -> {
                    val updatedModels = providerRepository.getModelsForProvider(providerId)
                    _uiState.update {
                        it.copy(
                            models = updatedModels,
                            showAddModelDialog = false,
                            manualModelIdInput = "",
                            successMessage = "模型已添加。"
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun deleteManualModel(modelId: String) {
        viewModelScope.launch {
            when (val result = providerRepository.deleteManualModel(providerId, modelId)) {
                is AppResult.Success -> {
                    val updatedModels = providerRepository.getModelsForProvider(providerId)
                    _uiState.update { it.copy(models = updatedModels) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun deleteProvider() {
        viewModelScope.launch {
            when (val result = providerRepository.deleteProvider(providerId)) {
                is AppResult.Success -> {
                    // 导航返回 -- 由 UI 观察导航事件处理
                    _uiState.update { it.copy(successMessage = "提供商已删除。") }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    private fun maskApiKey(apiKey: String?): String {
        if (apiKey.isNullOrEmpty()) return ""
        if (apiKey.length <= 8) return "****${apiKey.takeLast(4)}"
        val prefix = apiKey.take(3)
        val suffix = apiKey.takeLast(4)
        return "$prefix....$suffix"
    }
}
```

### Screen Composable 大纲

以下是结构大纲。完整的 Compose 代码将在实现阶段生成，参考 [UI 设计规范](../../design/ui-design-spec-zh.md) 获取精确的间距、颜色和样式。

#### ProviderListScreen

```kotlin
@Composable
fun ProviderListScreen(
    viewModel: ProviderListViewModel = koinViewModel(),
    onProviderClick: (String) -> Unit,     // 导航到提供商详情
    onAddCustomProvider: () -> Unit,       // 导航到创建自定义提供商
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = { IconButton(onClick = onAddCustomProvider) { Icon(Icons.Default.Add, "Add") } }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else {
            LazyColumn(contentPadding = padding) {
                // 预配置提供商区域
                val preConfigured = uiState.providers.filter { it.isPreConfigured }
                items(preConfigured, key = { it.id }) { provider ->
                    ProviderListItem(provider, onClick = { onProviderClick(provider.id) })
                }

                // 自定义提供商区域
                val custom = uiState.providers.filter { !it.isPreConfigured }
                if (custom.isNotEmpty()) {
                    stickyHeader { SectionHeader("CUSTOM") }
                    items(custom, key = { it.id }) { provider ->
                        ProviderListItem(provider, onClick = { onProviderClick(provider.id) })
                    }
                }
            }
        }
    }
}
```

#### ProviderDetailScreen

```kotlin
@Composable
fun ProviderDetailScreen(
    viewModel: ProviderDetailViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.provider?.name ?: "") },
                navigationIcon = { BackButton(onNavigateBack) }
            )
        },
        snackbarHost = { /* 用于成功/错误消息 */ }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // API Key 区域
            item { ApiKeySection(uiState, viewModel) }

            // 测试连接按钮 + 结果
            item { TestConnectionSection(uiState, viewModel) }

            // 模型列表
            item { SectionHeader("AVAILABLE MODELS") }
            items(uiState.models, key = { "${it.id}_${it.providerId}" }) { model ->
                ModelListItem(
                    model = model,
                    isDefault = model.id == uiState.globalDefaultModelId
                              && model.providerId == uiState.globalDefaultProviderId,
                    onSetDefault = { viewModel.setDefaultModel(model.id) },
                    onDelete = if (model.source == ModelSource.MANUAL)
                                  ({ viewModel.deleteManualModel(model.id) }) else null
                )
            }
            item { RefreshModelsButton(uiState.isRefreshingModels, viewModel::refreshModels) }
            item { AddManualModelButton(viewModel) }

            // 启用/禁用切换
            item { ActiveToggleSection(uiState.isActive, viewModel::toggleProviderActive) }

            // 删除按钮（仅自定义提供商）
            if (uiState.isPreConfigured == false) {
                item { DeleteProviderButton(viewModel::deleteProvider) }
            }
        }
    }
}
```

#### SetupScreen

```kotlin
@Composable
fun SetupScreen(
    onComplete: () -> Unit,     // 导航到聊天
    onSkip: () -> Unit          // 导航到聊天（跳过设置）
) {
    // 使用本地状态或 SetupViewModel
    // 第1步：选择提供商（OpenAI、Anthropic、Gemini、自定义的提供商卡片）
    // 第2步：输入 API key，测试连接
    // 第3步：选择默认模型，点击"开始使用"
    // 底部有"暂时跳过"按钮
}
```

Setup 页面复用 `ProviderDetailViewModel` 的逻辑（测试连接、获取模型、设置默认）。可以使用自己的专用 ViewModel 或共享组件。实现时建议使用专用的 `SetupViewModel` 以保持流程状态简洁自包含。

## Koin 依赖注入

```kotlin
// 现有 Koin 模块的补充

// AppModule.kt
val appModule = module {
    single { ApiKeyStorage(androidContext()) }
    single { ModelApiAdapterFactory(get()) }  // get() = OkHttpClient
}

// DatabaseModule.kt -- 带回调的 AppDatabase 创建
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "oneclaw.db")
            .addCallback(AppDatabaseCallback())
            .build()
    }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().modelDao() }
    // ... 其他 DAO
}

// RepositoryModule.kt
val repositoryModule = module {
    single<ProviderRepository> {
        ProviderRepositoryImpl(get(), get(), get(), get())
        // ProviderDao, ModelDao, ApiKeyStorage, ModelApiAdapterFactory
    }
}

// FeatureModule.kt -- 提供商功能
val featureModule = module {
    // 用例
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }

    // ViewModel
    viewModel { ProviderListViewModel(get(), get()) }
    viewModel { ProviderDetailViewModel(get(), get(), get(), get(), get(), get()) }
}
```

## 首次设置流程

### 检测逻辑

应用在启动时判断是否显示 Setup 页面：

```kotlin
// 在 MainActivity 或 NavGraph 设置中
val settingsRepository: SettingsRepository = get()
val hasCompletedSetup = settingsRepository.getBoolean("has_completed_setup", false)

if (!hasCompletedSetup) {
    // 导航到 Setup 页面
    navController.navigate(Route.Setup.path)
} else {
    // 导航到 Chat（新对话）
    navController.navigate(Route.NewChat.path)
}
```

### 完成设置

用户完成设置（点击"开始使用"）或跳过时：

```kotlin
// 在 SetupViewModel 或 SetupScreen 中
settingsRepository.setBoolean("has_completed_setup", true)
// 导航到聊天
```

一旦 `has_completed_setup` 为 `true`，Setup 页面就不会再显示。该设置存储在 `app_settings` Room 表中。

### 跳过行为

用户点击"暂时跳过"时：
1. `has_completed_setup` 设为 `true`
2. 导航到聊天页面
3. 用户可以浏览和管理 Agent，但无法发送消息
4. 当用户尝试在没有活跃已配置提供商的情况下发送消息时，出现内联错误：
   - "未配置提供商。前往设置添加。"
   - 错误消息链接到 设置 > 管理提供商

## 数据流示例

### 流程 1：用户向预配置提供商添加 API Key

```
1. 用户导航：设置 > 管理提供商 > OpenAI
2. ProviderDetailScreen 加载
   -> ProviderDetailViewModel.loadProvider()
   -> 从 ProviderDao 读取 ProviderEntity（已有种子数据）
   -> 从 ApiKeyStorage 读取 API key（null -- 未设置）
   -> UI 显示：API key 字段为空，"未配置"状态

3. 用户输入 API key，点击保存
   -> ProviderDetailViewModel.saveApiKey(key)
   -> ApiKeyStorage.setApiKey("provider-openai", key)
   -> UI 更新掩码显示

4. 用户点击"测试连接"
   -> ProviderDetailViewModel.testConnection()
   -> TestConnectionUseCase("provider-openai")
   -> ProviderRepositoryImpl.testConnection()
      -> 从 DAO 读取 Provider
      -> 从 ApiKeyStorage 读取 API key
      -> 从工厂获取 OpenAiAdapter
      -> 调用 adapter.testConnection() -> adapter.listModels()
         -> HTTP GET https://api.openai.com/v1/models
         -> 解析响应，过滤聊天模型
      -> 返回 ConnectionTestResult(success=true, modelCount=12)
   -> UI 显示："连接成功。发现 12 个模型。"

5. 自动刷新模型（由成功测试触发）
   -> ProviderDetailViewModel.refreshModels()
   -> FetchModelsUseCase("provider-openai")
   -> ProviderRepositoryImpl.fetchModelsFromApi()
      -> 删除该提供商现有的 DYNAMIC 模型
      -> 插入新的 DYNAMIC 模型
   -> 返回合并后的模型列表（DYNAMIC + PRESET）
   -> UI 显示更新后的模型列表

6. 用户选择默认模型（点击 gpt-4o 旁边的星号）
   -> ProviderDetailViewModel.setDefaultModel("gpt-4o")
   -> SetDefaultModelUseCase("gpt-4o", "provider-openai")
   -> ModelDao.updateDefaultModel()（清除旧默认，设置新默认）
```

### 流程 2：用户添加自定义提供商

```
1. 用户导航：设置 > 管理提供商 > [+] 添加
2. UI 显示表单：名称、API 基础 URL、协议类型（OPENAI/ANTHROPIC/GEMINI）
3. 用户填写：
   - 名称："My Local Ollama"
   - URL："http://192.168.1.100:11434/v1"
   - 协议：OPENAI（Ollama 兼容 OpenAI）

4. 创建提供商：
   -> ProviderRepositoryImpl.createProvider(Provider(
        id = UUID.randomUUID(),
        name = "My Local Ollama",
        type = ProviderType.OPENAI,
        apiBaseUrl = "http://192.168.1.100:11434/v1",
        isPreConfigured = false,
        isActive = true,
        ...
      ))

5. 用户输入 API key（或留空，如果端点不需要）
6. 用户手动添加模型（因为自定义端点可能不支持 /models）
   -> ProviderDetailViewModel.addManualModel("llama3", "Llama 3")
7. 用户按需测试连接
```

## 错误处理

### 错误场景和用户提示

| 场景 | ErrorCode | 用户提示 | UI 行为 |
|------|-----------|----------|---------|
| 无效 API key（401/403）| AUTH_ERROR | "认证失败。请检查您的 API key。" | 显示在连接测试结果区域 |
| 网络不可达 | NETWORK_ERROR | "无法连接到服务器。请检查 URL 和网络连接。" | 显示在连接测试结果区域 |
| 请求超时 | TIMEOUT_ERROR | "连接超时。" | 显示在连接测试结果区域 |
| API 返回空模型列表 | PROVIDER_ERROR | "未找到模型。使用预设模型。" | 静默回退到预设模型 |
| 删除默认提供商的模型 | VALIDATION_ERROR | "无法删除全局默认模型。" | 以 Snackbar 显示 |
| 删除预配置提供商 | VALIDATION_ERROR | "无法删除预配置提供商。" | 以 Snackbar 显示 |
| 重复的手动模型 | VALIDATION_ERROR | "该模型已存在于此提供商。" | 以 Snackbar 显示 |
| 未配置提供商（发送消息）| VALIDATION_ERROR | "未配置提供商。前往设置添加。" | 聊天中的内联错误 |

### API Key 格式验证

在测试连接前，执行基本的格式验证：

```kotlin
fun validateApiKeyFormat(key: String, providerType: ProviderType): String? {
    if (key.isBlank()) return "API key 不能为空。"
    return when (providerType) {
        ProviderType.OPENAI -> {
            if (!key.startsWith("sk-")) "OpenAI key 通常以 'sk-' 开头。"
            else null
        }
        ProviderType.ANTHROPIC -> {
            if (!key.startsWith("sk-ant-")) "Anthropic key 通常以 'sk-ant-' 开头。"
            else null
        }
        ProviderType.GEMINI -> null  // Gemini key 没有一致的前缀
    }
}
```

这是**软提醒**，不是阻止性验证。即使格式不匹配，key 仍然可以保存和测试（某些代理服务使用非标准的 key 格式）。

## 实现步骤

### 阶段 1：数据层
1. [ ] 在 `data/local/entity/` 创建 `ProviderEntity` 和 `ModelEntity`
2. [ ] 在 `data/local/dao/` 创建 `ProviderDao` 和 `ModelDao`
3. [ ] 在 `data/local/mapper/` 创建实体-领域映射器
4. [ ] 创建 `AppDatabaseCallback`，包含提供商和模型种子数据
5. [ ] 在 `AppDatabase` 中注册实体、DAO 和回调
6. [ ] 在 `data/security/` 创建 `ApiKeyStorage`
7. [ ] 在 `core/model/` 创建 `ConnectionTestResult` 和 `ConnectionErrorType`

### 阶段 2：提供商适配器
8. [ ] 在 `data/remote/adapter/` 创建 `ModelApiAdapter` 接口
9. [ ] 创建 DTO：`OpenAiModelListResponse`、`AnthropicModelListResponse`、`GeminiModelListResponse`
10. [ ] 实现 `OpenAiAdapter`（listModels + testConnection）
11. [ ] 实现 `AnthropicAdapter`（listModels + testConnection）
12. [ ] 实现 `GeminiAdapter`（listModels + testConnection）
13. [ ] 创建 `ModelApiAdapterFactory`

### 阶段 3：仓库与用例
14. [ ] 更新 `core/repository/` 中的 `ProviderRepository` 接口
15. [ ] 在 `data/repository/` 实现 `ProviderRepositoryImpl`
16. [ ] 在 `feature/provider/usecase/` 创建 `TestConnectionUseCase`
17. [ ] 创建 `FetchModelsUseCase`
18. [ ] 创建 `SetDefaultModelUseCase`

### 阶段 4：UI 层
19. [ ] 创建 `ProviderListUiState`、`ProviderDetailUiState`、`SetupUiState`
20. [ ] 实现 `ProviderListViewModel`
21. [ ] 实现 `ProviderDetailViewModel`
22. [ ] 实现 `ProviderListScreen`（Compose）
23. [ ] 实现 `ProviderDetailScreen`（Compose）
24. [ ] 实现 `SetupScreen`（Compose）
25. [ ] 在 NavGraph 中注册导航路由

### 阶段 5：DI 与集成
26. [ ] 更新 Koin 模块（AppModule、DatabaseModule、RepositoryModule、FeatureModule）
27. [ ] 在 MainActivity/NavGraph 中添加首次启动检测逻辑
28. [ ] 端到端测试：添加提供商 -> 测试连接 -> 获取模型 -> 设置默认

## 测试策略

### 单元测试
- `ApiKeyStorage`：验证 set/get/delete/has 操作
- `ProviderRepositoryImpl`：验证 CRUD、模型获取回退逻辑、删除限制
- `OpenAiAdapter.listModels()`：Mock OkHttp 响应，验证解析和过滤
- `AnthropicAdapter.listModels()`：Mock OkHttp 响应，验证解析
- `GeminiAdapter.listModels()`：Mock OkHttp 响应，验证模型 ID 提取
- `FetchModelsUseCase`：验证获取失败时回退到预设模型
- `SetDefaultModelUseCase`：验证验证检查
- `ProviderDetailViewModel`：验证所有操作的状态更新
- 实体-领域映射器：验证字段映射正确性

### 集成测试（Instrumented）
- 数据库种子数据：验证首次启动时创建 3 个提供商和 8 个预设模型
- API key 加密：验证 key 以加密方式存储且可正确读取
- 完整流程：创建提供商 -> 保存 key -> 测试 -> 获取模型 -> 设置默认

### UI 测试
- 提供商列表显示预配置提供商
- 提供商详情显示掩码 API key
- 眼睛图标切换 API key 可见性
- 测试连接显示加载状态然后显示结果
- 模型列表正确显示来源标签
- Setup 页面流程：选择提供商 -> 输入 key -> 测试 -> 选择模型 -> 完成
- Setup 页面跳过导航到聊天

## 安全考虑

1. **API key 不存入 Room**：Key 仅存储在 EncryptedSharedPreferences 中。providers 表没有 api_key 列。
2. **不记录日志**：API key 绝对不能出现在日志中，即使在 debug 构建中也是如此。如需调试日志，使用 `maskApiKey()`。
3. **HTTPS 强制**：所有提供商 API 调用使用 HTTPS。使用 HTTP URL 的自定义提供商应在 UI 中显示警告（但不阻止，因为本地端点如 Ollama 可能使用 HTTP）。
4. **内存**：从 EncryptedSharedPreferences 读取的 API key 不应缓存在长生命周期的变量中。按需读取、使用、丢弃。

## 依赖关系

### 依赖的 RFC
- **RFC-000（整体架构）**：项目结构、Koin 配置、Room 数据库、OkHttp 客户端

### 被依赖的 RFC
- **RFC-001（聊天交互）**：需要 `ModelApiAdapter.sendMessageStream()`、提供商/模型解析
- **RFC-002（Agent 管理）**：Agent 引用首选提供商/模型
- **RFC-004（工具系统）**：工具定义由适配器格式化为提供商特定的 API 调用
- **RFC-005（会话管理）**：会话追踪使用了哪个提供商/模型

## 开放问题

- [ ] 是否需要支持分页的模型列表响应？（Anthropic 和 Gemini 在模型很多时可能分页。V1 可以只获取第一页。）
- [ ] 自定义提供商的 HTTP 警告：应该阻止 HTTP 还是仅警告？（建议：仅警告，因为本地端点通常使用 HTTP。）

## 与 RFC-000 的差异

本 RFC 引入了以下变更，需要反映回 RFC-000：

1. **移除 `ProviderType.CUSTOM`**：`ProviderType` 现在只有 `OPENAI`、`ANTHROPIC`、`GEMINI`。`type` 字段表示 API 协议格式，而非服务身份。`isPreConfigured` 区分内置和用户创建的提供商。
2. **`ProviderRepository` 接口扩展**：新增 `addManualModel()`、`deleteManualModel()`、`setProviderActive()`、`getActiveProviders()`。
3. **新增 `ConnectionTestResult` 模型**：`core/model/` 中的新领域模型。
4. **`ModelApiAdapterFactory` 接收 `OkHttpClient`**：更新了构造函数签名。

## 参考资料

- [FEAT-003 PRD](../../prd/features/FEAT-003-provider-zh.md) -- 功能需求
- [UI 设计规范](../../design/ui-design-spec-zh.md) -- 第 3、4、7 节的视觉规范
- [RFC-000 整体架构](../architecture/RFC-000-overall-architecture-zh.md) -- 项目结构和数据模型
- [OpenAI Models API](https://platform.openai.com/docs/api-reference/models/list)
- [Anthropic Models API](https://docs.anthropic.com/en/api/models)
- [Gemini Models API](https://ai.google.dev/api/models)
- [EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
