package com.tomandy.palmclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class MemoryFileEntry(
    val name: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val isLongTerm: Boolean
)

class MemoryViewModel(
    private val workspaceDir: File
) : ViewModel() {

    private val memoryDir = File(workspaceDir, "memory")

    private val _memoryFiles = MutableStateFlow<List<MemoryFileEntry>>(emptyList())
    val memoryFiles: StateFlow<List<MemoryFileEntry>> = _memoryFiles

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent

    init {
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<MemoryFileEntry>()

            // MEMORY.md (long-term)
            val longTermFile = File(workspaceDir, "MEMORY.md")
            if (longTermFile.exists()) {
                entries.add(
                    MemoryFileEntry(
                        name = "MEMORY.md",
                        displayName = "Long-term Memory",
                        relativePath = "MEMORY.md",
                        size = longTermFile.length(),
                        lastModified = longTermFile.lastModified(),
                        isLongTerm = true
                    )
                )
            }

            // Daily memory files (newest first)
            if (memoryDir.exists()) {
                memoryDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
                    ?.sortedByDescending { it.name }
                    ?.forEach { file ->
                        val datePart = file.nameWithoutExtension
                        val displayName = try {
                            LocalDate.parse(datePart)
                                .format(
                                    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                                )
                        } catch (_: Exception) {
                            datePart
                        }
                        entries.add(
                            MemoryFileEntry(
                                name = file.name,
                                displayName = displayName,
                                relativePath = "memory/${file.name}",
                                size = file.length(),
                                lastModified = file.lastModified(),
                                isLongTerm = false
                            )
                        )
                    }
            }

            _memoryFiles.value = entries
        }
    }

    fun readFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(workspaceDir, relativePath)
            _fileContent.value = if (file.exists()) file.readText() else ""
        }
    }

    fun deleteFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(workspaceDir, relativePath)
            if (file.exists()) {
                file.delete()
            }
            loadFiles()
        }
    }
}
