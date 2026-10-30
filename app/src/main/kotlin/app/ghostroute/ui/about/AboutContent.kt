package app.ghostroute.ui.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.ghostroute.BuildConfig
import app.ghostroute.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P1-3b: About content — static, code-verified facts. Version rides
 * [AboutViewModel] (BuildConfig-generated, so it can never drift from the
 * build); credits and the network disclosure are constants here because the
 * BINDING is "verify in code before writing text": every license below was
 * checked against the resolved dependency POM (MapLibre 13.7.1 POM declares
 * BSD-2-Clause; the androidx/kotlin/squareup artifacts declare Apache-2.0),
 * and the three network services match exactly what GeocodeClient /
 * IpLocationClient / the map style URL actually call. Services get a null
 * license (nothing bundled => nothing claimed — omitted, never invented).
 */
object AboutContent {

    data class Credit(
        /** Proper name — intentionally not localized. */
        val name: String,
        /** Localized role label. */
        val roleRes: Int,
        /** Verified SPDX id for bundled code; null for remote services. */
        val license: String?,
    )

    /**
     * P0 (user, 2026-10-27): the four IDENTITY entries the About page must
     * always carry as working links — pinned by AboutLinksTest so they can
     * never silently disappear again. URLs dictated verbatim by the user;
     * the developer row opens his GitHub profile.
     */
    data class IdentityLink(
        /** Localized/plain row label. */
        val label: String,
        /** Exact destination — opens via ACTION_VIEW. */
        val url: String,
        /** Optional secondary line (e.g. the Net Republic community description). */
        val subtitle: String? = null,
    )

    const val NET_REPUBLIC_DESC =
        "Net Republic — technology and free/open-internet community"

    val IDENTITY_LINKS = listOf(
        IdentityLink("Developer: Amirreza", "https://github.com/amrsh88"),
        IdentityLink("GitHub", "https://github.com/amrsh88"),
        IdentityLink("Telegram Channel", "https://t.me/net_republic"),
        IdentityLink(
            "Telegram Community",
            "https://t.me/netrepublic",
            subtitle = NET_REPUBLIC_DESC,
        ),
    )

    val CREDITS = listOf(
        Credit("MapLibre", R.string.about_credit_role_map, "BSD-2-Clause"),
        Credit("OpenFreeMap · OpenStreetMap", R.string.about_credit_role_tiles, null),
        Credit("Photon · Nominatim", R.string.about_credit_role_search, null),
        Credit("ipwho.is", R.string.about_credit_role_ip, null),
        Credit("routing.openstreetmap.de", R.string.about_credit_role_routing, null),
        Credit("Kotlin", R.string.about_credit_role_core, "Apache-2.0"),
        Credit("Jetpack Compose", R.string.about_credit_role_core, "Apache-2.0"),
        Credit("OkHttp", R.string.about_credit_role_core, "Apache-2.0"),
        Credit("Room", R.string.about_credit_role_core, "Apache-2.0"),
        Credit("DataStore", R.string.about_credit_role_core, "Apache-2.0"),
    )

    /**
     * The exhaustive list of network destinations (privacy section + the
     * diagnostics export use the same constant — one truth, two faces).
     */
    const val NETWORK_DISCLOSURE =
        "OpenFreeMap · OpenStreetMap (map tiles) · Photon · Nominatim (place search) · ipwho.is (IP lookup) · routing.openstreetmap.de (road routing, only while Follow roads is on)"
}

/**
 * P1-3b: About state — only the build identity, read straight from
 * [BuildConfig]. Static assets live in [AboutContent].
 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {

    data class State(val versionName: String, val versionCode: Int)

    private val _state = MutableStateFlow(State(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
    val state: StateFlow<State> = _state
}
