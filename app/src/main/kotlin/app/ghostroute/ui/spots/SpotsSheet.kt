package app.ghostroute.ui.spots

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.SpotCsvCodec
import app.ghostroute.coredata.SpotCsvResult
import app.ghostroute.coredata.SpotCsvRow
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.FolderEntity
import app.ghostroute.coredata.db.RecentEntity
import app.ghostroute.coredata.db.SpotEntity
import app.ghostroute.coredata.minutesAgo
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.CoordinateStyle
import app.ghostroute.ui.settings.ContentResolverGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Task 8: the spots manage sheet — an in-box overlay sibling of SearchSheet
 * (dark scrim dismiss, slate rounded sheet in the thumb zone). Structure, top
 * down: title row (title + Done) · recents chips (when any) · folder tabs ·
 * spot rows. A spot tap jumps the pin; a long-press opens the context menu
 * (rename / move / delete — delete confirms, with haptics on the destructive
 * verdict). Icons stay Canvas-drawn per the Task 6 no-icon-font ruling, but
 * this sheet needs none: words carry it.
 */
@Composable
fun SpotsSheet(
    viewModel: SpotsViewModel,
    onJump: (lat: Double, lng: Double, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val errorRes by viewModel.errorRes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val csvGateway = remember(context) { ContentResolverGateway(context) }
    var pendingCsvImport by remember { mutableStateOf<List<SpotCsvRow>?>(null) }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            val csv = try {
                viewModel.exportCsv()
            } catch (_: Exception) {
                null
            }
            val written = if (csv == null) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                false
            } else {
                csvGateway.write(uri, csv)
            }
            Toast.makeText(
                context,
                context.getString(if (written) R.string.csv_export_success else R.string.csv_io_error),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val importCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val csv = readCsvLimited(context, uri)
            if (csv == null) {
                Toast.makeText(context, R.string.csv_io_error, Toast.LENGTH_SHORT).show()
            } else {
                when (val parsed = withContext(Dispatchers.Default) { SpotCsvCodec.parse(csv) }) {
                    is SpotCsvResult.Invalid ->
                        Toast.makeText(context, R.string.error_csv_invalid, Toast.LENGTH_SHORT).show()
                    is SpotCsvResult.Parsed -> pendingCsvImport = parsed.rows
                }
            }
        }
    }

    // P1-2d folder context targets (tab overflow menu → dialogs below).
    var folderRenameTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<FolderEntity?>(null) }
    val scrim = Color.Black.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(onClick = { }) // swallow taps inside the sheet
                .ghostSheet() // P5d: shared elevated sheet treatment
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TitleRow(
                onCreateFolder = viewModel::createFolder,
                onDone = onDismiss,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = {
                    importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "text/plain"))
                }) { Text(stringResource(R.string.csv_import_button)) }
                TextButton(onClick = { exportCsvLauncher.launch("ghostroute-spots.csv") }) {
                    Text(stringResource(R.string.csv_export_button))
                }
            }

            RecentsSection(
                recents = state.recents,
                onTap = { recent ->
                    viewModel.onRecentTapped(recent) { onJump(it.lat, it.lng, it.label) }
                },
                onDelete = { viewModel.deleteRecent(it.id) },
                onClearAll = { viewModel.clearRecents() },
                onClearManualPins = viewModel::clearManualPinRecents,
            )

            errorRes?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::consumeError)
                        .padding(vertical = 2.dp),
                )
            }

            FolderTabs(
                folders = state.folders,
                selectedId = state.selectedFolderId,
                defaultId = viewModel.defaultFolderIdOrNull(),
                onSelect = viewModel::selectFolder,
                onRenameFolder = { folderRenameTarget = it },
                onDeleteFolder = { folderDeleteTarget = it },
            )

            if (state.loading) {
                LoadingRow()
            } else if (state.spots.isEmpty()) {
                SheetMessage(text = stringResource(R.string.spots_empty))
            } else {
                SpotsList(
                    spots = state.spots,
                    onTap = { spot -> viewModel.onSpotTapped(spot) { onJump(it.lat, it.lng, it.name) } },
                    onRename = { spot, name -> viewModel.renameSpot(spot.id, name) },
                    onMove = { spot, folderId -> viewModel.moveSpot(spot.id, folderId) },
                    onDelete = { spot -> viewModel.deleteSpot(spot.id) },
                    onEditLocation = { spot, lat, lng -> viewModel.updateSpotLocation(spot.id, lat, lng) },
                    folders = state.folders,
                    defaultId = viewModel.defaultFolderIdOrNull(),
                )
            }
        }
    }

    // P1-2d: folder rename (Shared NameDialog) and delete (naming confirm —
    // NO-CASCADE semantics are stated in the copy: unfiled spots are kept).
    folderRenameTarget?.let { folder ->
        NameDialog(
            title = stringResource(R.string.folder_rename_title),
            hint = stringResource(R.string.folder_name_hint),
            initial = folder.name,
            confirmLabel = stringResource(R.string.dialog_save),
            onConfirm = {
                viewModel.renameFolder(folder.id, it)
                folderRenameTarget = null
            },
            onDismiss = { folderRenameTarget = null },
        )
    }

    folderDeleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderDeleteTarget = null },
            title = { Text(stringResource(R.string.folder_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.folder_delete_text,
                        if (folder.id == viewModel.defaultFolderIdOrNull()) {
                            stringResource(R.string.spots_default_folder)
                        } else {
                            folder.name
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    folderDeleteTarget = null
                }) {
                    Text(text = stringResource(R.string.menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderDeleteTarget = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    pendingCsvImport?.let { rows ->
        AlertDialog(
            onDismissRequest = { pendingCsvImport = null },
            title = { Text(stringResource(R.string.csv_import_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.csv_import_preview_count, rows.size, rows.size))
                    Text(
                        text = rows.take(5).joinToString(" · ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = viewModel.importCsvRows(rows)
                        val message = result.errorRes?.let { context.getString(it) }
                            ?: context.resources.getQuantityString(
                                R.plurals.csv_imported_count,
                                result.importedCount,
                                result.importedCount,
                            )
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        pendingCsvImport = null
                    }
                }) { Text(stringResource(R.string.csv_import_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCsvImport = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun TitleRow(onCreateFolder: (String) -> Unit, onDone: () -> Unit) {
    var showNewFolder by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.spots_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = { showNewFolder = true },
            shape = RoundedCornerShape(GhostRadius.pill),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(stringResource(R.string.folder_new))
        }
        TextButton(
            onClick = onDone,
            shape = RoundedCornerShape(GhostRadius.pill),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(stringResource(R.string.spots_done))
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = stringResource(R.string.folder_new),
            hint = stringResource(R.string.folder_name_hint),
            confirmLabel = stringResource(R.string.dialog_save),
            onConfirm = onCreateFolder,
            onDismiss = { showNewFolder = false },
        )
    }
}

/**
 * P1-2c recents voice. Chips show the stored label (fallback: coordinates for
 * blank labels — e.g. raw coordinate intakes and pre-v2 migrated rows) with a
 * relative age. Long-press deletes one chip; "Clear" wipes the list behind a
 * naming confirm dialog. Empty state keeps the section present with a hint —
 * no dead look (Part 1 rule 4; words carry it per the no-icon-font ruling).
 */
@Composable
private fun RecentsSection(
    recents: List<RecentEntity>,
    onTap: (RecentEntity) -> Unit,
    onDelete: (RecentEntity) -> Unit,
    onClearAll: () -> Unit,
    onClearManualPins: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearManual by remember { mutableStateOf(false) }
    val manualPinCount = recents.count { it.label == SpotRepository.MANUAL_PIN_RECENT_LABEL }
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.spots_recents),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recents.isNotEmpty()) {
                if (manualPinCount > 0) {
                    TextButton(
                        onClick = { confirmClearManual = true },
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.spots_recents_clear_manual)) }
                }
                TextButton(
                    onClick = { confirmClear = true },
                    shape = RoundedCornerShape(GhostRadius.pill),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.spots_recents_clear))
                }
            }
        }

        if (recents.isEmpty()) {
            Text(
                text = stringResource(R.string.spots_recents_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recents, key = { it.id }) { recent ->
                    RecentChip(
                        recent = recent,
                        nowMs = System.currentTimeMillis(),
                        onTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTap(recent)
                        },
                        onDelete = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete(recent)
                        },
                    )
                }
            }
        }
    }

    if (confirmClearManual) {
        AlertDialog(
            onDismissRequest = { confirmClearManual = false },
            title = { Text(stringResource(R.string.spots_recents_clear_manual_title)) },
            text = { Text(pluralStringResource(R.plurals.spots_recents_clear_manual_text, manualPinCount, manualPinCount)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearManual = false
                    onClearManualPins()
                }) { Text(stringResource(R.string.spots_recents_clear_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearManual = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.spots_recents_clear_title)) },
            text = { Text(stringResource(R.string.spots_recents_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text(stringResource(R.string.spots_recents_clear_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** One recent chip: label-first text (fallback coords) + relative age; long-press removes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentChip(
    recent: RecentEntity,
    nowMs: Long,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(GhostRadius.card),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onTap,
            onLongClick = onDelete,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = recent.label.ifBlank {
                    stringResource(R.string.format_coords_3, recent.lat, recent.lng)
                },
                style = CoordinateStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            val age = minutesAgo(recent.usedAt, nowMs)
            if (age != null) {
                Text(
                    text = stringResource(R.string.spots_recents_ago, age),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FolderTabs(
    folders: List<FolderEntity>,
    selectedId: Long?,
    defaultId: Long?,
    onSelect: (Long) -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit,
) {
    if (folders.isEmpty()) return
    val selectedIndex = folders.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    var menuFolderId by remember { mutableStateOf<Long?>(null) }

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { },
    ) {
        folders.forEach { folder ->
            val isDefault = folder.id == defaultId
            Tab(
                selected = folder.id == selectedId,
                onClick = { onSelect(folder.id) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isDefault) {
                                stringResource(R.string.spots_default_folder)
                            } else {
                                folder.name
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        // P1-2d: the built-in folder is the unfiled bucket and stays put —
                        // only user folders get rename/delete.
                        if (!isDefault) {
                            Box {
                                Text(
                                    text = "⋮",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { menuFolderId = folder.id }
                                        .padding(start = 6.dp),
                                )
                                DropdownMenu(
                                    expanded = menuFolderId == folder.id,
                                    onDismissRequest = { menuFolderId = null },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_rename)) },
                                        onClick = {
                                            menuFolderId = null
                                            onRenameFolder(folder)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.folder_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuFolderId = null
                                            onDeleteFolder(folder)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotsList(
    spots: List<SpotEntity>,
    folders: List<FolderEntity>,
    defaultId: Long?,
    onTap: (SpotEntity) -> Unit,
    onRename: (SpotEntity, String) -> Unit,
    onMove: (SpotEntity, Long?) -> Unit,
    onDelete: (SpotEntity) -> Unit,
    onEditLocation: (SpotEntity, Double, Double) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var renameTarget by remember { mutableStateOf<SpotEntity?>(null) }
    var moveTarget by remember { mutableStateOf<SpotEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SpotEntity?>(null) }
    var editLocationTarget by remember { mutableStateOf<SpotEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(spots) { spot ->
            var showMenu by remember(spot.id) { mutableStateOf(false) }
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onTap(spot) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = spot.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.format_coords_4, spot.lat, spot.lng),
                        style = CoordinateStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_rename)) },
                        onClick = {
                            showMenu = false
                            renameTarget = spot
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_edit_location)) },
                        onClick = {
                            showMenu = false
                            editLocationTarget = spot
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_move)) },
                        onClick = {
                            showMenu = false
                            moveTarget = spot
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.menu_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            deleteTarget = spot
                        },
                    )
                }
            }
        }
    }

    renameTarget?.let { spot ->
        NameDialog(
            title = stringResource(R.string.dialog_rename_title),
            hint = stringResource(R.string.rename_hint),
            initial = spot.name,
            confirmLabel = stringResource(R.string.dialog_save),
            onConfirm = {
                onRename(spot, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    moveTarget?.let { spot ->
        MoveDialog(
            spot = spot,
            folders = folders,
            defaultId = defaultId,
            onMove = { folderId ->
                onMove(spot, folderId)
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    editLocationTarget?.let { spot ->
        CoordsDialog(
            title = stringResource(R.string.menu_edit_location),
            initialLat = spot.lat,
            initialLng = spot.lng,
            confirmLabel = stringResource(R.string.dialog_save),
            onConfirm = { lat, lng ->
                onEditLocation(spot, lat, lng)
                editLocationTarget = null
            },
            onDismiss = { editLocationTarget = null },
        )
    }

    deleteTarget?.let { spot ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.delete_confirm_text, spot.name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete(spot)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.dialog_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/** Shared inline-textfield dialog used for folder creation and spot rename. */
/**
 * The shared name-entry dialog (title + hint + save/cancel). Private in Task 8,
 * now [internal] so the P2-1b timeline rename dialog reuses the exact pattern
 * instead of duplicating it.
 */
@Composable
internal fun NameDialog(
    title: String,
    hint: String,
    initial: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(GhostRadius.card),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * P1-2d: spot coordinate editor (NameDialog pattern, two numeric fields).
 * Local validation mirrors the repo guard (finite + ±85/±180): refuse with an
 * inline error — nothing is committed until the fields parse clean.
 */
@Composable
private fun CoordsDialog(
    title: String,
    initialLat: Double,
    initialLng: Double,
    confirmLabel: String,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var latText by remember { mutableStateOf(initialLat.toString()) }
    var lngText by remember { mutableStateOf(initialLng.toString()) }
    var invalid by remember { mutableStateOf(false) }

    fun parse(): Pair<Double, Double>? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lng = lngText.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite()) return null
        if (abs(lat) > SpotRepository.MAX_LAT || abs(lng) > SpotRepository.MAX_LNG) return null
        return lat to lng
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = {
                        latText = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_latitude)) },
                    singleLine = true,
                    shape = RoundedCornerShape(GhostRadius.card),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = lngText,
                    onValueChange = {
                        lngText = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_longitude)) },
                    singleLine = true,
                    shape = RoundedCornerShape(GhostRadius.card),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.dialog_coords_invalid),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = parse()
                if (parsed == null) {
                    invalid = true
                } else {
                    onConfirm(parsed.first, parsed.second)
                }
            }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * Move-to-folder chooser: every folder plus the "no folder" escape hatch
 * (where folder-delete survivors live). Current home is ticked.
 */
@Composable
private fun MoveDialog(
    spot: SpotEntity,
    folders: List<FolderEntity>,
    defaultId: Long?,
    onMove: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_title)) },
        text = {
            Column {
                folders.forEach { folder ->
                    val current = spot.folderId == folder.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMove(folder.id) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (folder.id == defaultId) {
                                stringResource(R.string.spots_default_folder)
                            } else {
                                folder.name
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (current) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                if (spot.folderId != null) {
                    Text(
                        text = stringResource(R.string.move_unfiled),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMove(null) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
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
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * [SpotsPill]: the map-screen entry — a small icon pill sibling of the search
 * pill (same slate surface, hairline border, teal Canvas glyph, no icon-font
 * dep per the Task 6 ruling).
 */
@Composable
fun SpotsPill(onClick: () -> Unit) {
    // P3a: glyph-only pill — the accessible name is MANDATORY (Pill.kt); the
    // bare bookmark used to be silent to TalkBack (deferred since P2-3).
    GhostPill(
        onClick = onClick,
        contentDescriptionRes = R.string.pill_spots,
    ) {
        BookmarkGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas bookmark/banner glyph (Task 6 ruling: icons drawn on Canvas). */
@Composable
private fun BookmarkGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val inset = stroke / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(inset, inset)
            lineTo(size.width - inset, inset)
            lineTo(size.width - inset, size.height - inset)
            lineTo(size.width / 2f, size.height * 0.62f)
            lineTo(inset, size.height - inset)
            close()
        }
        drawPath(path, color = color, style = Stroke(stroke))
    }
}

private suspend fun readCsvLimited(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            var tooLarge = false
            while (!tooLarge) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > SpotCsvCodec.MAX_BYTES) {
                    tooLarge = true
                } else {
                    output.write(buffer, 0, count)
                }
            }
            if (tooLarge) null else String(output.toByteArray(), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}
