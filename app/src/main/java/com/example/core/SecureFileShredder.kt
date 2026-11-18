package com.example.core

import com.example.data.ZeroLogEngine
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Forensics-resistant file & directory shredder for private local caches (voice notes,
 * generated containers, attachments). Strictly confines shredding to [cacheDir] and [filesDir]
 * via canonical path checks so external URIs or traversal paths are never touched.
 */
object SecureFileShredder {
    private val random = SecureRandom()
    private const val CHUNK_SIZE = 8192

    fun shredFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val length = file.length()
            if (length > 0L) {
                val junk = ByteArray(minOf(length, CHUNK_SIZE.toLong()).toInt())
                RandomAccessFile(file, "rw").use { raf ->
                    var remaining = length
                    while (remaining > 0L) {
                        val toWrite = minOf(remaining, junk.size.toLong()).toInt()
                        random.nextBytes(junk)
                        raf.write(junk, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.seek(0L)
                    junk.fill(0)
                    remaining = length
                    while (remaining > 0L) {
                        val toWrite = minOf(remaining, junk.size.toLong()).toInt()
                        raf.write(junk, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
            file.delete()
        } catch (_: Exception) {
            try {
                file.delete()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun shredLocalMedia(cacheDir: File, filesDir: File, pathOrUri: String?): Boolean {
        if (pathOrUri.isNullOrBlank()) return false
        if (pathOrUri.startsWith("content://", ignoreCase = true)) {
            // External SAF / Photo Picker URIs are read-only streams owned by OS providers.
            return false
        }
        val cleanPath = if (pathOrUri.startsWith("file://", ignoreCase = true)) {
            pathOrUri.removePrefix("file://")
        } else {
            pathOrUri
        }
        return try {
            val target = File(cleanPath).canonicalFile
            val allowedRoots = listOf(cacheDir.canonicalPath, filesDir.canonicalPath)
            val isInsideSandbox = allowedRoots.any { root ->
                target.path.startsWith(root + File.separator)
            }
            if (!isInsideSandbox) {
                false
            } else {
                shredFile(target)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shredDirectoryContents(directory: File): Int {
        var count = 0
        try {
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    count += shredDirectoryContents(child)
                    child.delete()
                } else {
                    if (shredFile(child)) {
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            ZeroLogEngine.log("Clean", "Directory shred warning: ${e.message}", "WARN")
        }
        return count
    }
}
