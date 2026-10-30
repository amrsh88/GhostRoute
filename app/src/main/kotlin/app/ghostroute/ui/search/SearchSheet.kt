package app.ghostroute.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.db.SearchHistoryEntity
import app.ghostroute.coredata.net.PlaceLabels
import app.ghostroute.coredata.net.PlaceResult
import app.ghostroute.engine.Waypoint
import app.ghostroute.engine.parseCoords
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.TelemetryStyle
import kotlinx.coroutines.delay

/**
 * Task 7: the search sheet (DESIGN §5 "top search pill" realized as a modal
 * overlay — retrains nothing, owns the thumb zone). Three voices, one each:
 * a rounded query field (top), results as calm name + dim-detail rows, and a
 * coordinate-entry row at the bottom for the strict [parseCoords] path.
 *
 * Theme discipline (Task 6 ledger): slate surfaces, teal reserved for live
 * signals — here the pin-drop action; rows are borderless, tap = commit.
 */
@Composable
fun SearchSheet(
    viewModel: SearchViewModel,
    onPlace: (PlaceResult) -> Unit,
    onCoords: (Waypoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val queryText by viewModel.query.collectAsState()
    val scrim = Color.Black.copy(alpha = 0.55f)
    // P2-4b: the clipboard lives in :app only (binding) — result rows and
    // history rows long-press-copy their coordinates as "lat,lng" (the same
    // strict form parseCoords/SET_ROUTE accept, so a copy can be re-pasted).
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val copyCoords: (PlaceResult) -> Unit = { place ->
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", "${place.lat},${place.lng}"))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copied = true
    }

    // P2-4b: pasted coordinates resolve to an instant pick — consume once.
    LaunchedEffect(state.coordPick) {
        state.coordPick?.let { waypoint ->
            viewModel.onCoordPickConsumed()?.let { picked ->
                onCoords(picked)
                onDismiss()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(scrim)
            .clickable(onClick = onDismiss) // tap the dark = leave
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(onClick = { }) // swallow taps inside the sheet
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(GhostRadius.sheet),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QueryField(viewModel)

            when {
                state.loading -> LoadingRow()
                state.error -> SheetMessage(text = stringResource(R.string.search_error))
                state.empty -> SheetMessage(text = stringResource(R.string.search_empty))
                state.places.isNotEmpty() -> ResultsList(
                    places = state.places,
                    ambiguous = state.ambiguous,
                    onTap = { place ->
                        if (viewModel.onPlacePicked(place)) {
                            onPlace(place)
                            onDismiss()
                        }
                    },
                    onCopyCoords = copyCoords,
                )
                // P2-4a: idle blank query — the persisted search history.
                queryText.isBlank() && state.history.isNotEmpty() -> HistoryList(
                    history = state.history,
                    onTap = viewModel::onHistorySelected,
                    onClear = { confirmClearHistory = true },
                    onCopy = { text ->
                        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        copied = true
                    },
                )
            }

            if (confirmClearHistory) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmClearHistory = false },
                    title = { Text(stringResource(R.string.search_history_clear_title)) },
                    text = { Text(stringResource(R.string.search_history_clear_text)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearHistory(); confirmClearHistory = false }) {
                            Text(stringResource(R.string.search_history_clear))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearHistory = false }) { Text(stringResource(R.string.dialog_cancel)) }
                    },
                )
            }

            if (copied) {
                Text(
                    text = stringResource(R.string.search_copied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            CoordinateRow(
                onCoords = { waypoint, literal ->
                    viewModel.onCoordRowPicked(waypoint, literal)
                    onCoords(waypoint)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun QueryField(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    OutlinedTextField(
        value = query,
        onValueChange = viewModel::onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        placeholder = {
            Text(
                text = stringResource(R.string.search_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(GhostRadius.pill),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * The results voice: name in ink, then the real geographic context line
 * (P2-4c/d: city · region · country from the RESULT'S OWN fields — a missing
 * field renders as an explicit dash, never a guess). Rows whose name collides
 * with another result (P2-4c) highlight their context line — that line is the
 * disambiguator. Long-press = copy the row's coordinates.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultsList(
    places: List<PlaceResult>,
    ambiguous: Set<String>,
    onTap: (PlaceResult) -> Unit,
    onCopyCoords: (PlaceResult) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(places) { place ->
            val isAmbiguous = ambiguous.contains(place.name.trim().lowercase())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onTap(place) },
                        onLongClick = { onCopyCoords(place) },
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (place.detail.isNotBlank()) {
                    Text(
                        text = place.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = PlaceLabels.geoContext(place),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAmbiguous) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isAmbiguous) FontWeight.SemiBold else null,
                )
            }
        }
    }
}

/**
 * P2-4a: the persisted search history (blank query, newest first). Tap re-runs
 * the query through the normal pipeline; long-press copies the row's
 * coordinates when the search resolved to a place (else the query text itself
 * — never an invented coordinate).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    history: List<SearchHistoryEntity>,
    onTap: (String) -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Column {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(history) { row ->
                val coords = if (row.lat != null && row.lng != null) "${row.lat},${row.lng}" else null
                val label = row.label // local val: label is a cross-module public API property, no smart cast
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onTap(row.query) },
                            onLongClick = { onCopy(coords ?: row.query) },
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = row.query,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (label != null) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (coords != null) {
                        Text(
                            text = coords,
                            style = TelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SheetMessage(text: String) {
    // P3c: empty/error copy is announced (live region) without stealing focus.
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The map-screen entry point (brief: "button/icon comp opens sheet — match
 * FAB visual language"): a raised slate pill with a teal magnifier — the one
 * place the search voice shows before it's summoned.
 */
@Composable
fun SearchPill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.fab_search) {
        MagnifierGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas magnifier (Task 6 ruling: icons drawn on Canvas, no icon-font dep). */
@Composable
private fun MagnifierGlyph(color: Color) {
    val stroke = 1.8.dp
    Canvas(modifier = Modifier.size(16.dp)) {
        val r = size.minDimension * 0.32f
        val c = Offset(r + size.minDimension * 0.10f, r + size.minDimension * 0.10f)
        drawCircle(color = color, radius = r, center = c, style = Stroke(stroke.toPx()))
        val a = c + Offset(r, r) * 0.7071f
        val b = Offset(size.width - stroke.toPx(), size.height - stroke.toPx())
        drawLine(color = color, start = a, end = b, strokeWidth = stroke.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * The coordinate voice: a mono-ish entry field committed by "Set pin" through
 * engine [parseCoords]; bad input stays inline (never a crash, never a toast).
 * P2-4: the commit also lands in the query history under its own literal
 * (same channel as a pasted pick, [SearchViewModel.onCoordRowPicked]).
 */
@Composable
private fun CoordinateRow(onCoords: (Waypoint, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun commit() {
        val waypoint = parseCoords(text)
        if (waypoint == null) {
            invalid = true
        } else {
            onCoords(waypoint, text)
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_coord_hint),
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                isError = invalid,
                shape = RoundedCornerShape(GhostRadius.pill),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Ascii,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            TextButton(
                onClick = ::commit,
                shape = RoundedCornerShape(GhostRadius.pill),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.search_coord_apply))
            }
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.search_coord_invalid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
    }
}
