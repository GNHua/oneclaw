package com.tomandy.oneclaw.skill

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Watches [userSkillsDir] for skill file changes (CREATE/DELETE/MOVE of
 * subdirectories and CLOSE_WRITE/DELETE/MOVE of SKILL.md files inside them).
 * All events are debounced by [DEBOUNCE_MS] before invoking [onChanged].
 */
@Suppress("DEPRECATION") // String-based FileObserver ctor for API 26 compat
internal class SkillFileObserver(
    private val userSkillsDir: File,
    private val onChanged: () -> Unit
) {

    private val parentMask =
        FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM

    private val childMask =
        FileObserver.CLOSE_WRITE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM

    private var parentObserver: FileObserver? = null
    private val childObservers = mutableMapOf<String, FileObserver>()

    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { onChanged() }

    fun startWatching() {
        userSkillsDir.mkdirs()

        parentObserver = object : FileObserver(userSkillsDir.absolutePath, parentMask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val masked = event and ALL_EVENTS
                when (masked) {
                    CREATE, MOVED_TO -> {
                        val subdir = File(userSkillsDir, path)
                        if (subdir.isDirectory) {
                            addChildObserver(path)
                        }
                    }
                    DELETE, MOVED_FROM -> removeChildObserver(path)
                }
                scheduleReload()
            }
        }
        parentObserver?.startWatching()

        // Watch existing subdirectories
        userSkillsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            addChildObserver(dir.name)
        }
    }

    fun stopWatching() {
        handler.removeCallbacks(debounceRunnable)
        parentObserver?.stopWatching()
        parentObserver = null
        synchronized(childObservers) {
            childObservers.values.forEach { it.stopWatching() }
            childObservers.clear()
        }
    }

    private fun addChildObserver(dirName: String) {
        synchronized(childObservers) {
            if (childObservers.containsKey(dirName)) return
            val dir = File(userSkillsDir, dirName)
            val observer = object : FileObserver(dir.absolutePath, childMask) {
                override fun onEvent(event: Int, path: String?) {
                    scheduleReload()
                }
            }
            observer.startWatching()
            childObservers[dirName] = observer
        }
    }

    private fun removeChildObserver(dirName: String) {
        synchronized(childObservers) {
            childObservers.remove(dirName)?.stopWatching()
        }
    }

    private fun scheduleReload() {
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
