package dev.opentomac.shared.mac

import dev.opentomac.shared.pairing.KeyValueStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * File-backed [KeyValueStore] under Application Support. Keys map to files with a
 * sanitized name; values are stored as raw bytes. This is the local-only persistence
 * for the MVP; migrating to Keychain-backed storage is future hardening.
 */
@OptIn(ExperimentalForeignApi::class)
class MacKeyValueStore private constructor(private val root: Path) : KeyValueStore {
    private val mutex = Mutex()
    private val fs = FileSystem.SYSTEM

    override suspend fun put(key: String, value: ByteArray) {
        mutex.withLock {
            fs.createDirectories(root)
            fs.write(root / encode(key)) { write(value) }
        }
    }

    override suspend fun get(key: String): ByteArray? = mutex.withLock {
        val path = root / encode(key)
        if (!fs.exists(path)) null else fs.read(path) { readByteArray() }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            val path = root / encode(key)
            if (fs.exists(path)) fs.delete(path)
        }
    }

    override suspend fun keys(): List<String> = mutex.withLock {
        if (!fs.exists(root)) emptyList() else fs.list(root).map { decode(it.name) }
    }

    private fun encode(key: String): String =
        key.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun decode(name: String): String {
        val bytes = ByteArray(name.length / 2) { i ->
            name.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes.decodeToString()
    }

    companion object {
        fun default(): MacKeyValueStore {
            val base = "${NSHomeDirectory()}/Library/Application Support/opentomac/store".toPath()
            return MacKeyValueStore(base)
        }
    }
}
