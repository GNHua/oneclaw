package com.oneclaw.shadow.feature.file

import com.oneclaw.shadow.core.model.FileInfo
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.file.usecase.DeleteFileUseCase
import com.oneclaw.shadow.feature.file.usecase.ListFilesUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {

    private lateinit var listFilesUseCase: ListFilesUseCase
    private lateinit var deleteFileUseCase: DeleteFileUseCase
    private lateinit var fileRepository: FileRepository
    private val testDispatcher = StandardTestDispatcher()

    private val rootFileInfo = FileInfo(
        name = "notes.txt",
        absolutePath = "/data/user_files/notes.txt",
        relativePath = "notes.txt",
        isDirectory = false,
        size = 100L,
        lastModified = 1000L,
        mimeType = "text/plain"
    )

    private val subDirFileInfo = FileInfo(
        name = "scripts",
        absolutePath = "/data/user_files/scripts",
        relativePath = "scripts",
        isDirectory = true,
        size = 0L,
        lastModified = 2000L,
        mimeType = null,
        childCount = 2
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        listFilesUseCase = mockk()
        deleteFileUseCase = mockk()
        fileRepository = mockk()

        every { listFilesUseCase("") } returns AppResult.Success(listOf(rootFileInfo, subDirFileInfo))
        every { fileRepository.getTotalSize() } returns 100L
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads files for root directory`() = runTest {
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.files.size)
        assertEquals("", state.currentPath)
        assertNull(state.errorMessage)
    }

    @Test
    fun `init sets totalSize from repository`() = runTest {
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        assertEquals(100L, viewModel.uiState.value.totalSize)
    }

    @Test
    fun `navigateTo updates currentPath and reloads files`() = runTest {
        every { listFilesUseCase("scripts") } returns AppResult.Success(emptyList())
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.navigateTo("scripts")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("scripts", state.currentPath)
        assertEquals(0, state.files.size)
    }

    @Test
    fun `navigateUp goes to parent directory from subdirectory`() = runTest {
        every { listFilesUseCase("scripts") } returns AppResult.Success(emptyList())
        every { listFilesUseCase("") } returns AppResult.Success(listOf(rootFileInfo))
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.navigateTo("scripts")
        advanceUntilIdle()

        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `navigateUp from root stays at root`() = runTest {
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `deleteFile calls use case and reloads list on success`() = runTest {
        every { deleteFileUseCase("notes.txt") } returns AppResult.Success(Unit)
        every { listFilesUseCase("") } returnsMany listOf(
            AppResult.Success(listOf(rootFileInfo, subDirFileInfo)),
            AppResult.Success(listOf(subDirFileInfo)) // after deletion
        )
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.deleteFile(rootFileInfo)
        advanceUntilIdle()

        verify { deleteFileUseCase("notes.txt") }
        assertEquals(1, viewModel.uiState.value.files.size)
    }

    @Test
    fun `deleteFile sets errorMessage on failure`() = runTest {
        every { deleteFileUseCase("notes.txt") } returns AppResult.Error(message = "Permission denied")
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.deleteFile(rootFileInfo)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `error state is set when listing fails`() = runTest {
        every { listFilesUseCase("") } returns AppResult.Error(message = "IO error")
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `clearError removes error message`() = runTest {
        every { listFilesUseCase("") } returns AppResult.Error(message = "IO error")
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.clearError()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `pathSegments for root path returns single Files segment`() = runTest {
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        val segments = viewModel.uiState.value.pathSegments
        assertEquals(1, segments.size)
        assertEquals("Files", segments[0].name)
        assertEquals("", segments[0].relativePath)
    }

    @Test
    fun `pathSegments for nested path returns correct segments`() = runTest {
        every { listFilesUseCase("scripts/python") } returns AppResult.Success(emptyList())
        val viewModel = FileBrowserViewModel(listFilesUseCase, deleteFileUseCase, fileRepository)
        advanceUntilIdle()

        viewModel.navigateTo("scripts/python")
        advanceUntilIdle()

        val segments = viewModel.uiState.value.pathSegments
        assertEquals(3, segments.size)
        assertEquals("Files", segments[0].name)
        assertEquals("scripts", segments[1].name)
        assertEquals("scripts", segments[1].relativePath)
        assertEquals("python", segments[2].name)
        assertEquals("scripts/python", segments[2].relativePath)
    }
}
