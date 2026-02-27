# RFC-003: Model/Provider Management

## Document Information
- **RFC ID**: RFC-003
- **Related PRD**: [FEAT-003 (Model/Provider Management)](../../prd/features/FEAT-003-provider.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md) (Sections 3, 4, 7)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
Model/Provider Management is the foundational feature that enables all AI interactions in OneClawShadow. Before the user can send any message, they must have at least one provider configured with a valid API key. This RFC specifies the technical implementation for provider CRUD operations, API key secure storage, model list fetching, connection testing, the first-time setup flow, and the global default model selection.

This RFC covers the **provider management infrastructure only** -- it defines the `ModelApiAdapter` interface in full but only implements `listModels()` and `testConnection()`. The `sendMessageStream()` implementation is deferred to RFC-001 (Chat Interaction).

### Goals
1. Implement provider and model data persistence (Room entities, DAOs, repository)
2. Implement secure API key storage via EncryptedSharedPreferences
3. Implement provider API adapters for OpenAI, Anthropic, and Google Gemini (list-models and test-connection)
4. Implement pre-configured provider seeding on first launch
5. Implement connection testing with clear success/failure feedback
6. Implement dynamic model list fetching with preset fallback
7. Implement the global default model selection and persistence
8. Implement provider management UI (list screen, detail screen, setup screen)
9. Provide enough implementation detail for AI-assisted code generation

### Non-Goals
- Streaming chat message sending (`sendMessageStream` implementation) -- deferred to RFC-001
- Tool definition formatting for API calls -- deferred to RFC-004
- SSE streaming implementation -- deferred to RFC-001
- OAuth-based provider authentication
- Multi-key per provider
- Provider usage analytics
- Data sync / backup of provider configurations

## Technical Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                             │
│  SetupScreen  ProviderListScreen  ProviderDetailScreen       │
│       │              │                   │                    │
│       v              v                   v                    │
│  (shared)   ProviderListViewModel  ProviderDetailViewModel   │
├─────────────────────────────────────────────────────────────┤
│                       Domain Layer                           │
│  TestConnectionUseCase  FetchModelsUseCase                   │
│  SetDefaultModelUseCase  SeedProvidersUseCase                │
│       │                                                      │
│       v                                                      │
│  ProviderRepository (interface)                              │
├─────────────────────────────────────────────────────────────┤
│                        Data Layer                            │
│  ProviderRepositoryImpl                                      │
│       │           │            │                              │
│       v           v            v                              │
│  ProviderDao   ApiKeyStorage  ModelApiAdapterFactory         │
│  ModelDao                      ├── OpenAiAdapter             │
│                                ├── AnthropicAdapter          │
│                                └── GeminiAdapter             │
└─────────────────────────────────────────────────────────────┘
```

### Core Components

1. **ApiKeyStorage**
   - Responsibility: Secure read/write/delete of API keys
   - Backed by: EncryptedSharedPreferences (Android Keystore)
   - Interface: `getApiKey(providerId)`, `setApiKey(providerId, key)`, `deleteApiKey(providerId)`, `hasApiKey(providerId)`

2. **ProviderRepositoryImpl**
   - Responsibility: Orchestrates provider CRUD, model operations, delegates to DAO and adapters
   - Dependencies: ProviderDao, ModelDao, ApiKeyStorage, ModelApiAdapterFactory, SettingsDao

3. **ModelApiAdapter (interface) + Implementations**
   - Responsibility: Abstracts provider-specific API formats
   - Implementations: OpenAiAdapter, AnthropicAdapter, GeminiAdapter
   - This RFC implements: `listModels()`, `testConnection()`
   - Deferred to RFC-001: `sendMessageStream()`

4. **Pre-configured Provider Seeder**
   - Responsibility: Inserts 3 built-in provider templates into Room on first DB creation
   - Mechanism: Room `RoomDatabase.Callback.onCreate`

5. **Use Cases**
   - `TestConnectionUseCase`: Validates API key + endpoint reachability
   - `FetchModelsUseCase`: Fetches model list from provider API with preset fallback
   - `SetDefaultModelUseCase`: Sets the global default model/provider
   - `SeedProvidersUseCase`: Not a traditional use case -- handled by DB callback (see below)

## Data Model

### Domain Models

These models are defined in the Core module (`core/model/`). The `Provider` and `AiModel` models are already defined in RFC-000. This section documents the **updated** definitions.

#### Provider (Updated)

```kotlin
data class Provider(
    val id: String,                    // UUID
    val name: String,                  // Display name (e.g., "OpenAI", "My Local Server")
    val type: ProviderType,            // API protocol format: OPENAI, ANTHROPIC, GEMINI
    val apiBaseUrl: String,            // Base URL for API requests
    val isPreConfigured: Boolean,      // true = built-in template, false = user-created
    val isActive: Boolean,             // Whether this provider is enabled
    val createdAt: Long,               // Timestamp millis
    val updatedAt: Long                // Timestamp millis
)

enum class ProviderType {
    OPENAI,     // OpenAI-compatible API format (also used for custom OpenAI-compatible endpoints)
    ANTHROPIC,  // Anthropic API format
    GEMINI      // Google Gemini API format
}
// NOTE: There is no CUSTOM type. Custom endpoints choose OPENAI, ANTHROPIC, or GEMINI
// based on which API protocol they are compatible with. The `isPreConfigured` field
// distinguishes built-in templates from user-created providers.
```

**Change from RFC-000**: Removed `ProviderType.CUSTOM`. The `type` field now represents the API protocol format, not the service identity. User-created providers pick `OPENAI`, `ANTHROPIC`, or `GEMINI` based on which API format their endpoint is compatible with. `isPreConfigured` distinguishes built-in from user-created providers.

#### AiModel (No Change)

```kotlin
data class AiModel(
    val id: String,                    // Model identifier (e.g., "gpt-4o")
    val displayName: String?,          // Human-friendly name (e.g., "GPT-4o")
    val providerId: String,            // Which provider this belongs to
    val isDefault: Boolean,            // Whether this is the global default
    val source: ModelSource            // How this model was added
)

enum class ModelSource {
    DYNAMIC,   // Fetched from provider API
    PRESET,    // Pre-configured fallback
    MANUAL     // User-added
}
```

#### ConnectionTestResult (New)

```kotlin
data class ConnectionTestResult(
    val success: Boolean,
    val modelCount: Int?,              // Number of models found (on success)
    val errorType: ConnectionErrorType?,
    val errorMessage: String?
)

enum class ConnectionErrorType {
    AUTH_FAILURE,       // 401/403 -- invalid API key
    NETWORK_FAILURE,    // Cannot reach the server
    TIMEOUT,            // Request timed out
    UNKNOWN             // Other error
}
```

### Room Entities

#### ProviderEntity

```kotlin
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "type")
    val type: String,                  // "OPENAI", "ANTHROPIC", "GEMINI"
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
    val source: String                 // "DYNAMIC", "PRESET", "MANUAL"
)
```

### Database Schema

```sql
CREATE TABLE providers (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,               -- "OPENAI", "ANTHROPIC", "GEMINI"
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
    source TEXT NOT NULL,             -- "DYNAMIC", "PRESET", "MANUAL"
    PRIMARY KEY (id, provider_id),
    FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);

CREATE INDEX idx_models_provider_id ON models(provider_id);
```

### Entity-Domain Mappers

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

## API Key Storage

### ApiKeyStorage Class

API keys are stored in EncryptedSharedPreferences, completely separate from the Room database. This class is in the Data layer at `data/security/ApiKeyStorage.kt`.

```kotlin
class ApiKeyStorage(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "oneclaw_api_keys",                          // File name
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),  // Master key
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(providerId: String): String? {
        return prefs.getString("api_key_$providerId", null)
    }

    fun setApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString("api_key_$providerId", apiKey.trim()).apply()
    }

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove("api_key_$providerId").apply()
    }

    fun hasApiKey(providerId: String): Boolean {
        return prefs.getString("api_key_$providerId", null) != null
    }
}
```

### Key Design Decisions
- **Key format**: `api_key_{providerId}` -- simple, collision-free since providerId is UUID
- **Trim on save**: `apiKey.trim()` removes accidental whitespace (common user error when pasting keys)
- **EncryptedSharedPreferences**: Uses AES256-GCM for value encryption, AES256-SIV for key encryption, backed by Android Keystore master key
- **No caching**: Reads directly from EncryptedSharedPreferences each time. The underlying implementation has its own caching, and API key reads are infrequent enough that this is not a performance concern.

## DAO Interfaces

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

## Provider API Adapters

### ModelApiAdapter Interface

This is the full interface. `sendMessageStream()` is declared here but implemented in RFC-001.

```kotlin
interface ModelApiAdapter {

    /**
     * Fetch available models from the provider API.
     *
     * @param apiBaseUrl Base URL for the provider API (e.g., "https://api.openai.com/v1")
     * @param apiKey The user's API key for this provider
     * @return List of available models on success, or error
     */
    suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>>

    /**
     * Test connection to the provider. Typically calls listModels internally.
     *
     * @return ConnectionTestResult with success/failure details
     */
    suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult>

    /**
     * Send a chat completion request with streaming response.
     * IMPLEMENTATION DEFERRED TO RFC-001.
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

### OpenAI Adapter

#### List Models API

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

#### DTOs

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

#### Implementation

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
                                providerId = "",  // Set by caller
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> {
                    AppResult.Error(
                        message = "Authentication failed. Please check your API key.",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API error: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(
                message = "Cannot reach the server. Please check the URL and your network.",
                code = ErrorCode.NETWORK_ERROR,
                exception = e
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(
                message = "Connection timed out.",
                code = ErrorCode.TIMEOUT_ERROR,
                exception = e
            )
        } catch (e: Exception) {
            AppResult.Error(
                message = "Unexpected error: ${e.message}",
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
        // Implementation deferred to RFC-001
        throw NotImplementedError("sendMessageStream is implemented in RFC-001")
    }

    /**
     * Filter out non-chat models (embeddings, whisper, tts, dall-e, etc.)
     * Only keep models that are useful for chat completion.
     */
    private fun isRelevantOpenAiModel(modelId: String): Boolean {
        val chatPrefixes = listOf("gpt-", "o1", "o3", "o4", "chatgpt-")
        return chatPrefixes.any { modelId.startsWith(it) }
    }

    /**
     * Format model ID into a human-friendly display name.
     * e.g., "gpt-4o" -> "GPT-4o", "gpt-4o-mini" -> "GPT-4o Mini"
     */
    private fun formatOpenAiModelName(modelId: String): String {
        return modelId
            .replace("gpt-", "GPT-")
            .replace("-mini", " Mini")
    }
}
```

### Anthropic Adapter

#### List Models API

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

#### DTOs

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

#### Implementation

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
                            message = "Empty response body",
                            code = ErrorCode.PROVIDER_ERROR
                        )
                    val parsed = Json.decodeFromString<AnthropicModelListResponse>(body)
                    val models = parsed.data
                        .filter { it.type == "model" }
                        .map { dto ->
                            AiModel(
                                id = dto.id,
                                displayName = dto.displayName,
                                providerId = "",  // Set by caller
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> {
                    AppResult.Error(
                        message = "Authentication failed. Please check your API key.",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API error: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "Cannot reach the server.", code = ErrorCode.NETWORK_ERROR, exception = e)
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(message = "Connection timed out.", code = ErrorCode.TIMEOUT_ERROR, exception = e)
        } catch (e: Exception) {
            AppResult.Error(message = "Unexpected error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
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
        throw NotImplementedError("sendMessageStream is implemented in RFC-001")
    }
}
```

### Google Gemini Adapter

#### List Models API

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

Note: Gemini uses a query parameter for the API key instead of a header.

#### DTOs

```kotlin
// data/remote/dto/gemini/

@Serializable
data class GeminiModelListResponse(
    val models: List<GeminiModelDto>
)

@Serializable
data class GeminiModelDto(
    val name: String,                  // e.g., "models/gemini-2.0-flash"
    val displayName: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)
```

#### Implementation

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
                            message = "Empty response body",
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
                                providerId = "",  // Set by caller
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 || response.code == 400 -> {
                    // Gemini returns 400 for invalid API key in some cases
                    AppResult.Error(
                        message = "Authentication failed. Please check your API key.",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API error: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "Cannot reach the server.", code = ErrorCode.NETWORK_ERROR, exception = e)
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(message = "Connection timed out.", code = ErrorCode.TIMEOUT_ERROR, exception = e)
        } catch (e: Exception) {
            AppResult.Error(message = "Unexpected error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
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
        throw NotImplementedError("sendMessageStream is implemented in RFC-001")
    }
}
```

## Pre-configured Provider Seeding

### Strategy: Room Database Callback

The three built-in provider templates (OpenAI, Anthropic, Google Gemini) are inserted into the Room database when the database is first created, via `RoomDatabase.Callback.onCreate`. This runs exactly once -- when the database file does not yet exist.

### Implementation

```kotlin
class AppDatabaseCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val now = System.currentTimeMillis()

        // Pre-configured provider templates
        // These are inserted without API keys -- keys are managed by ApiKeyStorage
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

        // Preset fallback models
        val models = listOf(
            // OpenAI preset models
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gpt-4o', 'GPT-4o', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('gpt-4o-mini', 'GPT-4o Mini', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('o1', 'o1', 'provider-openai', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('o3-mini', 'o3 Mini', 'provider-openai', 0, 'PRESET')""",

            // Anthropic preset models
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('claude-sonnet-4-20250514', 'Claude Sonnet 4', 'provider-anthropic', 0, 'PRESET')""",
            """INSERT INTO models (id, display_name, provider_id, is_default, source)
               VALUES ('claude-haiku-4-20250414', 'Claude Haiku 4', 'provider-anthropic', 0, 'PRESET')""",

            // Gemini preset models
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

### Design Decisions

- **Fixed IDs**: Pre-configured providers use deterministic IDs (`provider-openai`, `provider-anthropic`, `provider-gemini`) instead of random UUIDs. This makes it easy to reference them in code and avoids duplication if seeding logic ever runs again.
- **No API keys in seed data**: Providers are created in a "not configured" state. The user must add an API key to use them.
- **Preset models are fallbacks**: When the user adds an API key and the dynamic model list fetch succeeds, dynamic models are added alongside (not replacing) preset models. The UI can choose to show dynamic models first.
- **All providers start active**: Pre-configured providers are active by default. A provider without an API key is shown as "Not configured" in the UI but is still technically active.

## Repository Implementation

### ProviderRepository Interface

This is defined in Core module. Updated from RFC-000 to add `addManualModel` and `deleteManualModel`.

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
        // Check if this is the global default provider
        val defaultModel = modelDao.getDefaultModel().first()
        if (defaultModel != null && defaultModel.providerId == id) {
            return AppResult.Error(
                message = "Cannot delete the provider that has the global default model. Please set a different default model first.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        // Delete API key
        apiKeyStorage.deleteApiKey(id)

        // Delete from DB (CASCADE will delete associated models)
        val deleted = providerDao.deleteCustomProvider(id)
        return if (deleted > 0) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(
                message = "Cannot delete pre-configured providers.",
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
            ?: return AppResult.Error(message = "Provider not found", code = ErrorCode.VALIDATION_ERROR)

        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "No API key configured for this provider", code = ErrorCode.VALIDATION_ERROR)

        val adapter = adapterFactory.getAdapter(provider.type)
        val result = adapter.listModels(provider.apiBaseUrl, apiKey)

        return when (result) {
            is AppResult.Success -> {
                val modelsWithProvider = result.data.map { it.copy(providerId = providerId) }

                // Replace existing dynamic models, keep preset and manual models
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
                message = "Model '$modelId' already exists for this provider.",
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
            return AppResult.Error(message = "Model not found", code = ErrorCode.VALIDATION_ERROR)
        }
        if (model.source != ModelSource.MANUAL.name) {
            return AppResult.Error(
                message = "Only manually added models can be deleted.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (model.isDefault) {
            return AppResult.Error(
                message = "Cannot delete the global default model. Please change the default first.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        modelDao.deleteModelsBySource(providerId, ModelSource.MANUAL.name)
        return AppResult.Success(Unit)
    }

    override suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult> {
        val provider = providerDao.getProviderById(providerId)?.toDomain()
            ?: return AppResult.Error(message = "Provider not found", code = ErrorCode.VALIDATION_ERROR)

        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "No API key configured", code = ErrorCode.VALIDATION_ERROR)

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

## Use Cases

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
     * Fetch models from the provider API. If the fetch fails and the provider has
     * no existing models, preset models are already in the DB from seeding.
     * Returns the current model list (from DB) after the fetch attempt.
     */
    suspend operator fun invoke(providerId: String): AppResult<List<AiModel>> {
        // Attempt dynamic fetch
        val fetchResult = providerRepository.fetchModelsFromApi(providerId)

        // Regardless of fetch success/failure, return current model list from DB
        val currentModels = providerRepository.getModelsForProvider(providerId)

        return when (fetchResult) {
            is AppResult.Success -> AppResult.Success(currentModels)
            is AppResult.Error -> {
                if (currentModels.isNotEmpty()) {
                    // Fetch failed, but we have existing models (preset or previously fetched)
                    AppResult.Success(currentModels)
                } else {
                    // No models at all -- propagate error
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
        // Verify the model exists
        val models = providerRepository.getModelsForProvider(providerId)
        val model = models.find { it.id == modelId }
            ?: return AppResult.Error(
                message = "Model not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        // Verify the provider is active
        val provider = providerRepository.getProviderById(providerId)
            ?: return AppResult.Error(
                message = "Provider not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (!provider.isActive) {
            return AppResult.Error(
                message = "Cannot set a default model from an inactive provider.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        providerRepository.setGlobalDefaultModel(modelId, providerId)
        return AppResult.Success(Unit)
    }
}
```

## UI Layer

### Navigation Routes

```kotlin
// In Routes.kt (additions for provider feature)
object ProviderList : Route("providers")
object ProviderDetail : Route("providers/{providerId}") {
    fun create(providerId: String) = "providers/$providerId"
}
object Setup : Route("setup")
```

### UI State Definitions

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
    val hasApiKey: Boolean,           // Whether an API key is configured
    val connectionStatus: ConnectionStatus
)

enum class ConnectionStatus {
    CONNECTED,       // API key configured and last test was successful
    NOT_CONFIGURED,  // No API key set
    DISCONNECTED     // API key set but last test failed (or never tested)
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
    val apiKeyMasked: String = "",     // e.g., "sk-...abc1234"
    val apiKeyVisible: Boolean = false,
    val apiKeyFull: String = "",       // Full key (only loaded when visible)
    val isEditingApiKey: Boolean = false,
    val apiKeyInput: String = "",      // Input field value while editing

    // Connection test
    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,

    // Model list
    val isRefreshingModels: Boolean = false,

    // Provider state
    val isActive: Boolean = true,
    val isPreConfigured: Boolean = false,

    // Manual model add
    val showAddModelDialog: Boolean = false,
    val manualModelIdInput: String = "",

    // General
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
    CHOOSE_PROVIDER,    // Step 1: Pick OpenAI / Anthropic / Gemini / Custom
    ENTER_API_KEY,      // Step 2: Enter API key and test connection
    SELECT_MODEL        // Step 3: Select default model
}
```

### ViewModels

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
                        // NOTE: We don't automatically test connections on list load.
                        // Connection status can be updated when user navigates to detail.
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
                    successMessage = "API key saved."
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
                    // If connection succeeded, also fetch models
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
                    _uiState.update { it.copy(successMessage = "Default model set.") }
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
                            successMessage = "Model added."
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
                    // Navigate back -- handled by UI observing a navigation event
                    _uiState.update { it.copy(successMessage = "Provider deleted.") }
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

### Screen Composable Outlines

The following are structural outlines. Full Compose code will be generated during implementation, referencing the [UI Design Spec](../../design/ui-design-spec.md) for exact spacing, colors, and styling.

#### ProviderListScreen

```kotlin
@Composable
fun ProviderListScreen(
    viewModel: ProviderListViewModel = koinViewModel(),
    onProviderClick: (String) -> Unit,     // Navigate to provider detail
    onAddCustomProvider: () -> Unit,       // Navigate to create custom provider
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
                // Pre-configured providers section
                val preConfigured = uiState.providers.filter { it.isPreConfigured }
                items(preConfigured, key = { it.id }) { provider ->
                    ProviderListItem(provider, onClick = { onProviderClick(provider.id) })
                }

                // Custom providers section
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
        snackbarHost = { /* For success/error messages */ }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // API Key Section
            item { ApiKeySection(uiState, viewModel) }

            // Test Connection Button + Result
            item { TestConnectionSection(uiState, viewModel) }

            // Model List
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

            // Active Toggle
            item { ActiveToggleSection(uiState.isActive, viewModel::toggleProviderActive) }

            // Delete Button (custom providers only)
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
    onComplete: () -> Unit,     // Navigate to chat
    onSkip: () -> Unit          // Navigate to chat (skip setup)
) {
    // Uses local state or a SetupViewModel
    // Step 1: Choose provider (provider cards for OpenAI, Anthropic, Gemini, Custom)
    // Step 2: Enter API key, test connection
    // Step 3: Select default model, tap "Get Started"
    // "Skip for now" button at the bottom
}
```

The Setup screen reuses logic from `ProviderDetailViewModel` (test connection, fetch models, set default). It can either use its own dedicated ViewModel or share components. During implementation, a dedicated `SetupViewModel` is recommended to keep the flow state simple and self-contained.

## Koin Dependency Injection

```kotlin
// Additions to existing Koin modules

// AppModule.kt
val appModule = module {
    single { ApiKeyStorage(androidContext()) }
    single { ModelApiAdapterFactory(get()) }  // get() = OkHttpClient
}

// DatabaseModule.kt -- AppDatabase creation with callback
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "oneclaw.db")
            .addCallback(AppDatabaseCallback())
            .build()
    }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().modelDao() }
    // ... other DAOs
}

// RepositoryModule.kt
val repositoryModule = module {
    single<ProviderRepository> {
        ProviderRepositoryImpl(get(), get(), get(), get())
        // ProviderDao, ModelDao, ApiKeyStorage, ModelApiAdapterFactory
    }
}

// FeatureModule.kt -- Provider feature
val featureModule = module {
    // Use Cases
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }

    // ViewModels
    viewModel { ProviderListViewModel(get(), get()) }
    viewModel { ProviderDetailViewModel(get(), get(), get(), get(), get(), get()) }
}
```

## First-Time Setup Flow

### Detection Logic

The app determines whether to show the Setup screen on launch:

```kotlin
// In MainActivity or NavGraph setup
val settingsRepository: SettingsRepository = get()
val hasCompletedSetup = settingsRepository.getBoolean("has_completed_setup", false)

if (!hasCompletedSetup) {
    // Navigate to Setup screen
    navController.navigate(Route.Setup.path)
} else {
    // Navigate to Chat (new conversation)
    navController.navigate(Route.NewChat.path)
}
```

### Completing Setup

When the user finishes setup (taps "Get Started") or skips:

```kotlin
// In SetupViewModel or SetupScreen
settingsRepository.setBoolean("has_completed_setup", true)
// Navigate to chat
```

Once `has_completed_setup` is `true`, the Setup screen is never shown again. The setting is stored in the `app_settings` Room table.

### Skip Behavior

When the user taps "Skip for now":
1. `has_completed_setup` is set to `true`
2. Navigate to the chat screen
3. The user can browse and manage agents, but cannot send messages
4. When the user tries to send a message with no active configured provider, an inline error appears:
   - "No provider configured. Go to Settings to add one."
   - The error message links to Settings > Manage Providers

## Data Flow Examples

### Flow 1: User Adds API Key to Pre-configured Provider

```
1. User navigates: Settings > Manage Providers > OpenAI
2. ProviderDetailScreen loads
   -> ProviderDetailViewModel.loadProvider()
   -> Reads ProviderEntity from ProviderDao (already seeded)
   -> Reads API key from ApiKeyStorage (null -- not set)
   -> UI shows: API key field empty, "Not configured" status

3. User enters API key, taps Save
   -> ProviderDetailViewModel.saveApiKey(key)
   -> ApiKeyStorage.setApiKey("provider-openai", key)
   -> UI updates masked key display

4. User taps "Test Connection"
   -> ProviderDetailViewModel.testConnection()
   -> TestConnectionUseCase("provider-openai")
   -> ProviderRepositoryImpl.testConnection()
      -> Reads Provider from DAO
      -> Reads API key from ApiKeyStorage
      -> Gets OpenAiAdapter from factory
      -> Calls adapter.testConnection() -> adapter.listModels()
         -> HTTP GET https://api.openai.com/v1/models
         -> Parses response, filters chat models
      -> Returns ConnectionTestResult(success=true, modelCount=12)
   -> UI shows: "Connection successful. Found 12 models."

5. Auto-refresh models (triggered by successful test)
   -> ProviderDetailViewModel.refreshModels()
   -> FetchModelsUseCase("provider-openai")
   -> ProviderRepositoryImpl.fetchModelsFromApi()
      -> Deletes existing DYNAMIC models for this provider
      -> Inserts new DYNAMIC models
   -> Returns merged model list (DYNAMIC + PRESET)
   -> UI shows updated model list

6. User selects default model (taps star next to gpt-4o)
   -> ProviderDetailViewModel.setDefaultModel("gpt-4o")
   -> SetDefaultModelUseCase("gpt-4o", "provider-openai")
   -> ModelDao.updateDefaultModel() (clears old default, sets new)
```

### Flow 2: User Adds Custom Provider

```
1. User navigates: Settings > Manage Providers > [+] Add
2. UI shows a form: name, API base URL, protocol type (OPENAI/ANTHROPIC/GEMINI)
3. User fills in:
   - Name: "My Local Ollama"
   - URL: "http://192.168.1.100:11434/v1"
   - Protocol: OPENAI (Ollama is OpenAI-compatible)

4. Provider created:
   -> ProviderRepositoryImpl.createProvider(Provider(
        id = UUID.randomUUID(),
        name = "My Local Ollama",
        type = ProviderType.OPENAI,
        apiBaseUrl = "http://192.168.1.100:11434/v1",
        isPreConfigured = false,
        isActive = true,
        ...
      ))

5. User enters API key (or leaves empty if not required by endpoint)
6. User manually adds models (since custom endpoints may not support /models)
   -> ProviderDetailViewModel.addManualModel("llama3", "Llama 3")
7. User tests connection if applicable
```

## Error Handling

### Error Scenarios and User-Facing Messages

| Scenario | ErrorCode | User Message | UI Behavior |
|----------|-----------|--------------|-------------|
| Invalid API key (401/403) | AUTH_ERROR | "Authentication failed. Please check your API key." | Show in connection test result area |
| Network unreachable | NETWORK_ERROR | "Cannot reach the server. Please check the URL and your network." | Show in connection test result area |
| Request timeout | TIMEOUT_ERROR | "Connection timed out." | Show in connection test result area |
| Empty model list from API | PROVIDER_ERROR | "No models found. Using preset models." | Fall back to preset models silently |
| Delete default provider's model | VALIDATION_ERROR | "Cannot delete the global default model." | Show as Snackbar |
| Delete pre-configured provider | VALIDATION_ERROR | "Cannot delete pre-configured providers." | Show as Snackbar |
| Duplicate manual model | VALIDATION_ERROR | "Model already exists for this provider." | Show as Snackbar |
| No provider configured (send message) | VALIDATION_ERROR | "No provider configured. Go to Settings to add one." | Inline error in chat |

### API Key Validation

Before testing a connection, perform basic format validation:

```kotlin
fun validateApiKeyFormat(key: String, providerType: ProviderType): String? {
    if (key.isBlank()) return "API key cannot be empty."
    return when (providerType) {
        ProviderType.OPENAI -> {
            if (!key.startsWith("sk-")) "OpenAI keys typically start with 'sk-'."
            else null
        }
        ProviderType.ANTHROPIC -> {
            if (!key.startsWith("sk-ant-")) "Anthropic keys typically start with 'sk-ant-'."
            else null
        }
        ProviderType.GEMINI -> null  // Gemini keys don't have a consistent prefix
    }
}
```

This is a **soft warning**, not a blocking validation. The key can still be saved and tested even if the format doesn't match (some proxy services use non-standard key formats).

## Implementation Steps

### Phase 1: Data Layer
1. [ ] Create `ProviderEntity` and `ModelEntity` in `data/local/entity/`
2. [ ] Create `ProviderDao` and `ModelDao` in `data/local/dao/`
3. [ ] Create entity-domain mappers in `data/local/mapper/`
4. [ ] Create `AppDatabaseCallback` with provider and model seeding
5. [ ] Register entities, DAOs, and callback in `AppDatabase`
6. [ ] Create `ApiKeyStorage` in `data/security/`
7. [ ] Create `ConnectionTestResult` and `ConnectionErrorType` in `core/model/`

### Phase 2: Provider Adapters
8. [ ] Create `ModelApiAdapter` interface in `data/remote/adapter/`
9. [ ] Create DTOs: `OpenAiModelListResponse`, `AnthropicModelListResponse`, `GeminiModelListResponse`
10. [ ] Implement `OpenAiAdapter` (listModels + testConnection)
11. [ ] Implement `AnthropicAdapter` (listModels + testConnection)
12. [ ] Implement `GeminiAdapter` (listModels + testConnection)
13. [ ] Create `ModelApiAdapterFactory`

### Phase 3: Repository & Use Cases
14. [ ] Update `ProviderRepository` interface in `core/repository/`
15. [ ] Implement `ProviderRepositoryImpl` in `data/repository/`
16. [ ] Create `TestConnectionUseCase` in `feature/provider/usecase/`
17. [ ] Create `FetchModelsUseCase`
18. [ ] Create `SetDefaultModelUseCase`

### Phase 4: UI Layer
19. [ ] Create `ProviderListUiState`, `ProviderDetailUiState`, `SetupUiState`
20. [ ] Implement `ProviderListViewModel`
21. [ ] Implement `ProviderDetailViewModel`
22. [ ] Implement `ProviderListScreen` (Compose)
23. [ ] Implement `ProviderDetailScreen` (Compose)
24. [ ] Implement `SetupScreen` (Compose)
25. [ ] Register navigation routes in NavGraph

### Phase 5: DI & Integration
26. [ ] Update Koin modules (AppModule, DatabaseModule, RepositoryModule, FeatureModule)
27. [ ] Add first-launch detection logic in MainActivity/NavGraph
28. [ ] End-to-end testing: add provider -> test connection -> fetch models -> set default

## Testing Strategy

### Unit Tests
- `ApiKeyStorage`: Verify set/get/delete/has operations
- `ProviderRepositoryImpl`: Verify CRUD, model fetch fallback logic, delete restrictions
- `OpenAiAdapter.listModels()`: Mock OkHttp responses, verify parsing and filtering
- `AnthropicAdapter.listModels()`: Mock OkHttp responses, verify parsing
- `GeminiAdapter.listModels()`: Mock OkHttp responses, verify model ID extraction
- `FetchModelsUseCase`: Verify fallback to preset models on fetch failure
- `SetDefaultModelUseCase`: Verify validation checks
- `ProviderDetailViewModel`: Verify state updates for all actions
- Entity-domain mappers: Verify correct field mapping

### Integration Tests (Instrumented)
- Database seeding: Verify 3 providers and 8 preset models are created on first launch
- API key encryption: Verify keys are stored encrypted and retrievable
- Full flow: Create provider -> save key -> test -> fetch models -> set default

### UI Tests
- Provider list shows pre-configured providers
- Provider detail shows masked API key
- Eye icon toggles API key visibility
- Test connection shows loading state then result
- Model list displays correctly with source labels
- Setup screen flow: choose provider -> enter key -> test -> select model -> complete
- Setup screen skip navigates to chat

## Security Considerations

1. **API keys never in Room**: Keys are stored exclusively in EncryptedSharedPreferences. The providers table has no api_key column.
2. **No logging**: API keys must never appear in log statements, even in debug builds. Use `maskApiKey()` if logging is needed for debugging.
3. **HTTPS enforcement**: All provider API calls use HTTPS. Custom providers with HTTP URLs should show a warning in the UI (but not block, since local endpoints like Ollama may use HTTP).
4. **Memory**: API keys read from EncryptedSharedPreferences should not be cached in long-lived variables. Read on demand, use, discard.

## Dependencies

### Depends On
- **RFC-000 (Overall Architecture)**: Project structure, Koin setup, Room database, OkHttp client

### Depended On By
- **RFC-001 (Chat Interaction)**: Needs `ModelApiAdapter.sendMessageStream()`, provider/model resolution
- **RFC-002 (Agent Management)**: Agents reference preferred provider/model
- **RFC-004 (Tool System)**: Tool definitions formatted by adapter for provider-specific API calls
- **RFC-005 (Session Management)**: Sessions track which provider/model was used

## Open Questions

- [ ] Should we support paginated model list responses? (Anthropic and Gemini may paginate if there are many models. For V1, we can fetch only the first page.)
- [ ] HTTP warning for custom providers: Should we block HTTP or just warn? (Recommendation: warn only, since local endpoints commonly use HTTP.)

## Differences from RFC-000

This RFC introduces the following changes that need to be reflected back in RFC-000:

1. **`ProviderType.CUSTOM` removed**: `ProviderType` now only has `OPENAI`, `ANTHROPIC`, `GEMINI`. The `type` field represents API protocol format, not service identity. `isPreConfigured` distinguishes built-in from user-created providers.
2. **`ProviderRepository` interface expanded**: Added `addManualModel()`, `deleteManualModel()`, `setProviderActive()`, `getActiveProviders()`.
3. **`ConnectionTestResult` model added**: New domain model in `core/model/`.
4. **`ModelApiAdapterFactory` takes `OkHttpClient`**: Updated constructor signature.

## References

- [FEAT-003 PRD](../../prd/features/FEAT-003-provider.md) -- Functional requirements
- [UI Design Spec](../../design/ui-design-spec.md) -- Visual specifications for Sections 3, 4, 7
- [RFC-000 Overall Architecture](../architecture/RFC-000-overall-architecture.md) -- Project structure and data models
- [OpenAI Models API](https://platform.openai.com/docs/api-reference/models/list)
- [Anthropic Models API](https://docs.anthropic.com/en/api/models)
- [Gemini Models API](https://ai.google.dev/api/models)
- [EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
