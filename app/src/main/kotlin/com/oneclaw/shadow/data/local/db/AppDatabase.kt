package com.oneclaw.shadow.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.data.local.dao.AgentDao
import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.ModelDao
import com.oneclaw.shadow.data.local.dao.ProviderDao
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.data.local.dao.SettingsDao
import com.oneclaw.shadow.data.local.entity.AgentEntity
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.data.local.entity.MessageEntity
import com.oneclaw.shadow.data.local.entity.ModelEntity
import com.oneclaw.shadow.data.local.entity.ProviderEntity
import com.oneclaw.shadow.data.local.entity.SessionEntity
import com.oneclaw.shadow.data.local.entity.SettingsEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

@Database(
    entities = [
        AgentEntity::class,
        ProviderEntity::class,
        ModelEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        SettingsEntity::class,
        MemoryIndexEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun memoryIndexDao(): MemoryIndexDao

    companion object {
        fun createSeedCallback(): Callback {
            return object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    val now = System.currentTimeMillis()
                    val json = Json { ignoreUnknownKeys = true }

                    // Seed pre-configured providers
                    db.execSQL(
                        """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
                           VALUES ('provider-openai', 'OpenAI', 'OPENAI', 'https://api.openai.com/v1', 1, 1, $now, $now)"""
                    )
                    db.execSQL(
                        """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
                           VALUES ('provider-anthropic', 'Anthropic', 'ANTHROPIC', 'https://api.anthropic.com/v1', 1, 1, $now, $now)"""
                    )
                    db.execSQL(
                        """INSERT INTO providers (id, name, type, api_base_url, is_pre_configured, is_active, created_at, updated_at)
                           VALUES ('provider-gemini', 'Google Gemini', 'GEMINI', 'https://generativelanguage.googleapis.com/v1beta', 1, 1, $now, $now)"""
                    )

                    // Seed preset models (include context_window_size for fresh installs at schema v2)
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('gpt-4o', 'GPT-4o', 'provider-openai', 0, 'PRESET', 128000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('gpt-4o-mini', 'GPT-4o Mini', 'provider-openai', 0, 'PRESET', 128000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('o1', 'o1', 'provider-openai', 0, 'PRESET', 200000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('o3-mini', 'o3 Mini', 'provider-openai', 0, 'PRESET', 200000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('claude-opus-4-5-20251101', 'Claude Opus 4.5', 'provider-anthropic', 0, 'PRESET', 200000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('claude-sonnet-4-5-20250929', 'Claude Sonnet 4.5', 'provider-anthropic', 0, 'PRESET', 200000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('claude-haiku-4-5-20251001', 'Claude Haiku 4.5', 'provider-anthropic', 0, 'PRESET', 200000)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('gemini-2.0-flash', 'Gemini 2.0 Flash', 'provider-gemini', 0, 'PRESET', 1048576)")
                    db.execSQL("INSERT INTO models (id, display_name, provider_id, is_default, source, context_window_size) VALUES ('gemini-2.5-pro', 'Gemini 2.5 Pro', 'provider-gemini', 0, 'PRESET', 1048576)")

                    // Seed built-in General Assistant agent
                    val toolIds = json.encodeToString(listOf("get_current_time", "read_file", "write_file", "http_request"))
                    val systemPrompt = AgentConstants.GENERAL_ASSISTANT_SYSTEM_PROMPT.replace("'", "''")
                    db.execSQL(
                        """INSERT INTO agents (id, name, description, system_prompt, tool_ids, preferred_provider_id, preferred_model_id, is_built_in, created_at, updated_at)
                           VALUES ('${AgentConstants.GENERAL_ASSISTANT_ID}', '${AgentConstants.GENERAL_ASSISTANT_NAME}', 'A general-purpose AI assistant with access to all tools.', '$systemPrompt', '$toolIds', NULL, NULL, 1, $now, $now)"""
                    )
                }
            }
        }
    }
}
