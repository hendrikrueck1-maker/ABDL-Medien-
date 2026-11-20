package de.hr.mediathek.feature.overview

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hr.mediathek.core.ui.glassCardColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun gb(bytes: Long) = "%.1f GB".format(Locale.GERMANY, bytes / (1024.0 * 1024 * 1024))
private fun avg(v: Double?) = v?.let { "%.2f ★".format(Locale.GERMANY, it) } ?: "–"

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
private fun Block(title: String, lines: List<Pair<String, String>>) {
    Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            for ((k, v) in lines) {
                Row(Modifier.fillMaxWidth()) {
                    Text(k, Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
fun OverallStatsScreen(onBack: () -> Unit, viewModel: OverallStatsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Page("Gesamtstatistik", onBack) {
        val st = s
        if (st == null) {
            Text("Wird berechnet …")
        } else {
            Block("Videos", listOf(
                "Anzahl" to "${st.videos.count}", "Speicher" to gb(st.videos.bytes), "Ø Bewertung" to avg(st.videos.avgRating),
                "Ohne Bewertung" to "${st.videos.unrated}", "Ungesehen" to "${st.videos.unseen}", "Favoriten" to "${st.videos.favorites}",
                "Offline" to "${st.videos.offline}",
            ))
            Block("Bilder", listOf(
                "Anzahl" to "${st.images.count}", "Speicher" to gb(st.images.bytes), "Ø Bewertung" to avg(st.images.avgRating),
                "Ohne Bewertung" to "${st.images.unrated}", "Favoriten" to "${st.images.favorites}", "Offline" to "${st.images.offline}",
            ))
            Block("Geschichten", listOf(
                "Anzahl" to "${st.stories.count}", "Ø Bewertung" to avg(st.stories.avgRating), "Ohne Bewertung" to "${st.stories.unrated}",
                "Favoriten" to "${st.stories.favorites}", "Zum Weiterschreiben" to "${st.stories.continueCount}",
            ))
            Block("Produkte", listOf(
                "Anzahl" to "${st.products.count}", "Ø Sterne" to avg(st.products.avgStars), "Ohne Sterne" to "${st.products.unrated}",
                "Favoriten" to "${st.products.favorites}",
            ))
            Block("Tracker", listOf("Einträge" to "${st.trackerEntries}"))
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val secure by viewModel.secureScreen.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    Page("Datenschutz", onBack) {
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dateinamen verbergen", style = MaterialTheme.typography.titleMedium)
                Text("Benachrichtigungen und Mediensteuerung zeigen nie Dateinamen oder Titel, nur einen neutralen Text. Das ist immer aktiv.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bildschirm schützen", style = MaterialTheme.typography.titleMedium)
                    Text("Verhindert Screenshots und blendet die App in der Übersicht der zuletzt benutzten Apps aus.", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = secure, onCheckedChange = viewModel::setSecure,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Bildschirm schützen" },
                )
            }
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Galerie-Apps", style = MaterialTheme.typography.titleMedium)
                Text("Der Arbeitsordner, der Papierkorb und Mobil-Pakete bekommen eine .nomedia-Datei, damit Galerie-Apps die Inhalte nicht anzeigen. Eigene Medienordner solltest du selbst mit einer .nomedia-Datei versehen, wenn andere Apps sie nicht sehen sollen.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fehlerprotokoll teilen", style = MaterialTheme.typography.titleMedium)
                Text("Vorschau der letzten Einträge mit unkenntlich gemachten Dateinamen, Pfaden und Adressen.", style = MaterialTheme.typography.bodyMedium)
                val p = preview
                if (p == null) {
                    OutlinedButton(onClick = viewModel::loadPreview) { Text("Vorschau anzeigen") }
                } else {
                    if (p.isEmpty()) Text("Keine Einträge.")
                    for (line in p) Text(line, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::hidePreview) { Text("Schließen") }
                }
            }
        }
    }
}

@Composable
fun BackgroundScreen(onBack: () -> Unit, viewModel: BackgroundViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    Page("Hintergrundbetrieb", onBack) {
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Letzte Sicherung", style = MaterialTheme.typography.titleMedium)
                Text(s.lastBackup?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.GERMANY).format(Date(it)) } ?: "Noch keine Sicherung")
            }
        }
        Card(colors = glassCardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Akku-Optimierung", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.batteryExempt) "Die App ist von der Akku-Optimierung ausgenommen. Lange Arbeiten (Einlesen, Komprimieren, Paket) laufen zuverlässig weiter."
                    else "Die App wird vom System gebremst. Bei langen Arbeiten (Komprimieren, Paket) kann Android sie unterbrechen; sie werden dann später fortgesetzt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Akku-Einstellungen öffnen") }
            }
        }
    }
}
