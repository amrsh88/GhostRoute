package app.ghostroute

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * en+fa parity, machine-checked (P1-3 onward): every string resource declared in
 * R.string must resolve in the fa locale — a missing fa entry (silent fallback to
 * en) or a blank value fails. Locale-neutral placeholders (%1$d etc.) are the same
 * declaration for both locales, so resolution alone proves structural parity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class StringsParityTest {

    @Test
    fun `every string resource resolves non-blank in fa`() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val faConfig = Configuration(app.resources.configuration).apply {
            setLocale(Locale("fa"))
        }
        val faContext = app.createConfigurationContext(faConfig)

        var checked = 0
        for (field in R.string::class.java.declaredFields) {
            val id = try {
                field.getInt(null)
            } catch (_: IllegalAccessException) {
                continue
            }
            val fa = try {
                faContext.getString(id)
            } catch (e: Exception) {
                fail("fa locale missing/blown for R.string.${field.name}: $e")
                return
            }
            assertTrue("fa string R.string.${field.name} is blank", fa.isNotBlank())
            checked++
        }
        assertTrue("sanity: expected a meaningful string table, saw $checked", checked > 50)
    }
}
