package de.hr.mediathek.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import de.hr.mediathek.feature.storage.StorageSection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.R
import de.hr.mediathek.core.update.ApkInstaller
import de.hr.mediathek.core.ui.AppRoutes
import de.hr.mediathek.core.ui.GlassTitle
import de.hr.mediathek.core.ui.glass
import de.hr.mediathek.core.ui.glassCardColors
import de.hr.mediathek.feature.diag.DiagRoutes

@Composable
fun SettingsScreen(onOpenRoute: (String) -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassTitle(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.Start),
        )

        GroupTitle("Aussehen")
        ReadabilityCard()

        GroupTitle(stringResource(R.string.settings_group_media))
        SettingsRow(R.string.settings_row_sources, R.string.settings_row_sources_hint, GlassTint.BLUE) {
            onOpenRoute(AppRoutes.storage(StorageSection.SOURCES))
        }
        SettingsRow(R.string.settings_row_compression, R.string.settings_row_compression_hint, GlassTint.BLUE) {
            onOpenRoute(AppRoutes.storage(StorageSection.COMPRESSION))
        }

        SettingsRow(R.string.settings_row_package, R.string.settings_row_package_hint, GlassTint.BLUE) {
            onOpenRoute("packages")
        }

        GroupTitle(stringResource(R.string.settings_group_data))
        SettingsRow(R.string.settings_row_backup, R.string.settings_row_backup_hint, GlassTint.MINT) {
            onOpenRoute(AppRoutes.storage(StorageSection.BACKUP))
        }
        SettingsRow(R.string.settings_row_stats, R.string.settings_row_stats_hint, GlassTint.MINT) {
            onOpenRoute("overall-stats")
        }
        SettingsRow(R.string.settings_row_privacy, R.string.settings_row_privacy_hint, GlassTint.MINT) {
            onOpenRoute("privacy")
        }
        SettingsRow(R.string.settings_row_background, R.string.settings_row_background_hint, GlassTint.MINT) {
            onOpenRoute("background")
        }
        SettingsRow(R.string.settings_open_errorlog, R.string.settings_row_errorlog_hint, GlassTint.MINT) {
            onOpenRoute(AppRoutes.ERROR_LOG)
        }

        GroupTitle(stringResource(R.string.settings_group_app))
        Card(
            colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.update_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                if (BuildConfig.DEBUG) {
                    Text(
                        text = stringResource(R.string.update_debug_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    when (val s = state) {
                        UpdateState.Idle -> Unit
                        UpdateState.Checking -> {
                            Text(stringResource(R.string.update_checking), color = MaterialTheme.colorScheme.onSecondaryContainer)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        UpdateState.UpToDate -> Text(
                            stringResource(R.string.update_up_to_date),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        is UpdateState.Available -> Text(
                            stringResource(R.string.update_available, s.release.build),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        is UpdateState.Downloading -> {
                            Text(
                                stringResource(R.string.update_downloading, s.percent),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            LinearProgressIndicator(
                                progress = { s.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        is UpdateState.Ready -> Text(
                            stringResource(R.string.update_ready),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        is UpdateState.Error -> Text(
                            stringResource(R.string.update_error, s.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    when (val s = state) {
                        is UpdateState.Available -> Button(onClick = { viewModel.download(s.release) }) {
                            Text(stringResource(R.string.update_download))
                        }
                        is UpdateState.Ready -> Button(onClick = {
                            if (ApkInstaller.canInstall(context)) {
                                ApkInstaller.install(context, s.file)
                            } else {
                                ApkInstaller.openPermissionSettings(context)
                            }
                        }) {
                            Text(
                                stringResource(
                                    if (ApkInstaller.canInstall(context)) R.string.update_install
                                    else R.string.update_allow_install,
                                ),
                            )
                        }
                        is UpdateState.Downloading, UpdateState.Checking -> Unit
                        else -> Button(onClick = viewModel::check) {
                            Text(stringResource(R.string.update_check))
                        }
                    }
                }
            }
        }

        SettingsRow(R.string.diag_open_scan, R.string.diag_section_hint, GlassTint.PINK) { onOpenRoute(DiagRoutes.SCAN) }
        SettingsRow(R.string.diag_open_transcode, null, GlassTint.PINK) { onOpenRoute(DiagRoutes.TRANSCODE) }
        SettingsRow(R.string.diag_open_pdf, null, GlassTint.PINK) { onOpenRoute(DiagRoutes.PDF) }
    }
}

@Composable
private fun GroupTitle(text: String) {
    GlassTitle(
        text = text,
        tint = MaterialTheme.colorScheme.tertiaryContainer,
        onTint = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private enum class GlassTint { PINK, BLUE, MINT }

/** Eine Zeile der Einstellungsliste als farbige Glasfläche: Titel, kurzer Hinweis, Pfeil. */
@Composable
private fun SettingsRow(@StringRes title: Int, @StringRes hint: Int?, tint: GlassTint, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (tint) {
        GlassTint.PINK -> scheme.primaryContainer to scheme.onPrimaryContainer
        GlassTint.BLUE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        GlassTint.MINT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(container, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = onContainer)
            if (hint != null) {
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.8f))
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = onContainer)
    }
}

/** Regler für die Lesbarkeit der Schrift über dem Hintergrundbild. */
@Composable
private fun ReadabilityCard(vm: ReadabilityViewModel = hiltViewModel()) {
    val level by vm.level.collectAsStateWithLifecycle()
    val auto by vm.auto.collectAsStateWithLifecycle()
    Card(colors = glassCardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lesbarkeit der Schrift", style = MaterialTheme.typography.titleMedium)
            Text("Links: Hintergrundbild deutlich sichtbar. Rechts: Flächen hinter der Schrift fester.", style = MaterialTheme.typography.bodySmall)
            Slider(value = level, onValueChange = vm::setLevel, valueRange = 0f..1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = auto, onCheckedChange = vm::setAuto)
                Text("Automatisch je nach Bild (helle oder unruhige Bilder bekommen mehr Schutz)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
