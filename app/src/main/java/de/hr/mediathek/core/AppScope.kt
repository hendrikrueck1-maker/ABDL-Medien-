package de.hr.mediathek.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Bereich für kurze Arbeiten, die nicht abbrechen dürfen, wenn ein Bildschirm geschlossen wird
 * (Speichern beim Verlassen, Löschen von Paketdateien, Exporte). Lebt so lange wie der App-Prozess.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
