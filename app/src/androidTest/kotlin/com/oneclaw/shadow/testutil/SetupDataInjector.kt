package com.oneclaw.shadow.testutil

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneclaw.shadow.data.local.db.AppDatabase
import com.oneclaw.shadow.data.local.entity.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-shot instrumented helper that seeds the production database with:
 *  - has_completed_setup = true
 *  - Anthropic API key
 *  - claude-haiku-4-5-20251001 as the default model
 *
 * Run with:
 *   ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.oneclaw.shadow.testutil.SetupDataInjector
 */
@RunWith(AndroidJUnit4::class)
class SetupDataInjector {

    @Test
    fun injectSetupData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Write API key into debug plain SharedPreferences (readable by app in DEBUG builds)
        val apiKey = "YOUR_ANTHROPIC_API_KEY_HERE"
        context.getSharedPreferences("oneclaw_api_keys_debug", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("api_key_provider-anthropic", apiKey)
            .commit()

        // 2. Open the production database (same name as the app uses)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "oneclaw.db")
            .addCallback(AppDatabase.createSeedCallback())
            .build()

        val settingsDao = db.settingsDao()
        val modelDao = db.modelDao()

        // 3. Mark setup as completed
        settingsDao.set(SettingsEntity(key = "has_completed_setup", value = "true"))

        // 4. Set claude-haiku-4-5-20251001 as the default model
        modelDao.clearAllDefaults()
        modelDao.setDefault("claude-haiku-4-5-20251001", "provider-anthropic")

        db.close()

        println("SetupDataInjector: done")
    }
}
