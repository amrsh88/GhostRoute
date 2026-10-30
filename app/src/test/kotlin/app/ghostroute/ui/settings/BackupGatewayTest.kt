package app.ghostroute.ui.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class BackupGatewayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://backup-test/document")

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var bytesRead = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bytesRead += it }
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun `reads files up to inclusive byte cap and closes streams`() = runTest {
        for (size in listOf(0, MAX_BACKUP_BYTES - 1, MAX_BACKUP_BYTES)) {
            val stream = TrackingStream(ByteArray(size) { 'a'.code.toByte() })
            shadowOf(context.contentResolver).registerInputStream(uri, stream)
            val text = ContentResolverGateway(context).read(uri)
            assertEquals("size $size", size, text?.length)
            assertEquals(size, stream.bytesRead)
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `oversized unknown-length input returns null after only one excess byte`() = runTest {
        val stream = TrackingStream(ByteArray(MAX_BACKUP_BYTES + 100_000))
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        assertNull(ContentResolverGateway(context).read(uri))
        assertEquals(MAX_BACKUP_BYTES + 1, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test
    fun `cap counts UTF-8 bytes rather than characters`() = runTest {
        val unicode = "é".repeat(MAX_BACKUP_BYTES / 2)
        val accepted = TrackingStream(unicode.toByteArray(Charsets.UTF_8))
        shadowOf(context.contentResolver).registerInputStream(uri, accepted)
        assertEquals(unicode, ContentResolverGateway(context).read(uri))
        assertTrue(accepted.closed)
        val oversized = TrackingStream((unicode + "é").toByteArray(Charsets.UTF_8))
        shadowOf(context.contentResolver).registerInputStream(uri, oversized)
        assertNull(ContentResolverGateway(context).read(uri))
        assertEquals(MAX_BACKUP_BYTES + 1, oversized.bytesRead)
        assertTrue(oversized.closed)
    }

    @Test
    fun `read IO failure returns null and closes stream`() = runTest {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("injected read error")
            override fun close() { closed = true }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        assertNull(ContentResolverGateway(context).read(uri))
        assertTrue(closed)
    }
}
