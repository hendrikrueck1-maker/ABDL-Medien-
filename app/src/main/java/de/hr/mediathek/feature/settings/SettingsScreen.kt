package de.hr.mediathek.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import de.hr.mediathek.feature.diag.DiagRoutes

@Composable
fun SettingsScreen(onOpenRoute: (String) -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
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

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(onClick = { onOpenRoute(AppRoutes.STORAGE) }) {
                    Text(stringResource(R.string.settings_open_storage))
                }
                Button(onClick = { onOpenRoute(AppRoutes.ERROR_LOG) }) {
                    Text(stringResource(R.string.settings_open_errorlog))
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.diag_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.diag_section_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Button(onClick = { onOpenRoute(DiagRoutes.SCAN) }) {
                    Text(stringResource(R.string.diag_open_scan))
                }
                Button(onClick = { onOpenRoute(DiagRoutes.TRANSCODE) }) {
                    Text(stringResource(R.string.diag_open_transcode))
                }
                Button(onClick = { onOpenRoute(DiagRoutes.PDF) }) {
                    Text(stringResource(R.string.diag_open_pdf))
                }
            }
        }
    }
}
