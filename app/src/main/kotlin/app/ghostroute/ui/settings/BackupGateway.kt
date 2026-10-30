package app.ghostroute.ui.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * P2-5b: SAF document IO behind a seam (P2-5-E3, P2-4-E1 precedent) — the
 * content-resolver calls are :app-only (core-data stays android-UI-free) and
 * Robolectric can't exercise real providers, so the VM test injects the seam
 * while the codec/manager underneath stay fully real.
 *
 * Write semantics (atomic-write rule): the caller hands over the FULL
 * serialized document; [write] truncates ("wt"), writes once, flushes, and
 * only then reports success — a partial write is never reported as success.
 * On failure the possibly-zero-byte document is deleted best-effort, so no
 * half-written backup lingers in the picker.
 */
interface BackupGateway {
    /** Full UTF-8 text, or null when unreadable or larger than [MAX_BACKUP_BYTES]. */
    suspend fun read(uri: Uri): String?

    /** Full-document write; true ONLY after complete write + flush. */
    suspend fun write(uri: Uri, content: String): Boolean
}

/** Inclusive UTF-8 input cap (5 MiB); bounds raw input before JSON allocation. */
internal const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

class ContentResolverGateway(private val context: Context) : BackupGateway {

    override suspend fun read(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // Do not trust provider size metadata: some SAF providers report
                // no size (or an incorrect one). Read at most the cap + one byte
                // and propagate null rather than feeding a truncated file to import.
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_BYTES + 1 - total))
                    if (read < 0) break
                    if (read == 0) {
                        // Defensive progress for unusual providers returning zero.
                        val byte = stream.read()
                        if (byte < 0) break
                        total++
                        if (total > MAX_BACKUP_BYTES) return@use null
                        out.write(byte)
                    } else {
                        total += read
                        if (total > MAX_BACKUP_BYTES) return@use null
                        out.write(buffer, 0, read)
                    }
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    override suspend fun write(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@withContext false
            true
        } catch (t: Throwable) {
            // The provider may leave a truncated document behind — sweep it.
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            false
        }
    }
}
