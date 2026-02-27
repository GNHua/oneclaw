package com.oneclaw.shadow.data.local.dao

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.oneclaw.shadow.data.local.db.AppDatabase
import com.oneclaw.shadow.data.local.entity.ModelEntity
import com.oneclaw.shadow.data.local.entity.ProviderEntity
import com.oneclaw.shadow.testutil.TestDatabaseHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ProviderDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var providerDao: ProviderDao
    private lateinit var modelDao: ModelDao

    private fun createProvider(
        id: String = "provider-test",
        name: String = "Test Provider",
        isActive: Boolean = true
    ) = ProviderEntity(
        id = id,
        name = name,
        type = "ANTHROPIC",
        apiBaseUrl = "https://api.anthropic.com",
        isPreConfigured = false,
        isActive = isActive,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Before
    fun setup() {
        database = TestDatabaseHelper.createInMemoryDatabase(
            ApplicationProvider.getApplicationContext()
        )
        providerDao = database.providerDao()
        modelDao = database.modelDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndQueryProvider() = runTest {
        val provider = createProvider()
        providerDao.insert(provider)

        val result = providerDao.getProviderById("provider-test")
        assertNotNull(result)
        assertEquals("Test Provider", result!!.name)
        assertEquals("ANTHROPIC", result.type)
    }

    @Test
    fun queryActiveProviders() = runTest {
        providerDao.insert(createProvider(id = "p-1", name = "Active", isActive = true))
        providerDao.insert(createProvider(id = "p-2", name = "Inactive", isActive = false))
        providerDao.insert(createProvider(id = "p-3", name = "Active 2", isActive = true))

        providerDao.getActiveProviders().test {
            val active = awaitItem()
            assertEquals(2, active.size)
            assertEquals(true, active.all { it.isActive })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setActiveUpdatesProvider() = runTest {
        providerDao.insert(createProvider(id = "p-1", isActive = true))

        providerDao.setActive("p-1", false)

        val result = providerDao.getProviderById("p-1")
        assertEquals(false, result!!.isActive)
    }

    @Test
    fun cascadeDeleteRemovesModels() = runTest {
        providerDao.insert(createProvider(id = "p-cascade"))
        modelDao.insert(ModelEntity("model-1", "Model 1", "p-cascade", false, "DYNAMIC"))
        modelDao.insert(ModelEntity("model-2", "Model 2", "p-cascade", false, "DYNAMIC"))

        val modelsBefore = modelDao.getModelsForProvider("p-cascade")
        assertEquals(2, modelsBefore.size)

        providerDao.deleteCustomProvider("p-cascade")

        val modelsAfter = modelDao.getModelsForProvider("p-cascade")
        assertEquals(0, modelsAfter.size)
        assertNull(providerDao.getProviderById("p-cascade"))
    }

    @Test
    fun updateProvider() = runTest {
        providerDao.insert(createProvider())
        val updated = createProvider().copy(name = "Updated Name", updatedAt = 5000L)
        providerDao.update(updated)

        val result = providerDao.getProviderById("provider-test")
        assertEquals("Updated Name", result!!.name)
        assertEquals(5000L, result.updatedAt)
    }
}
