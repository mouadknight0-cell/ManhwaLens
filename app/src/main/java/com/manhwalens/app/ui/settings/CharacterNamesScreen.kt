package com.manhwalens.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manhwalens.app.R
import com.manhwalens.app.data.CharacterMemory
import com.manhwalens.app.data.SeriesPrefs
import com.manhwalens.app.names.CharacterRecord
import com.manhwalens.app.names.JsonCharacterStore
import com.manhwalens.app.names.Series
import com.manhwalens.app.names.SeriesCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1 — the minimal character-name editor.
 *
 * Two jobs only, matching the phase scope:
 *  1. pick/create the **series** the reader is curating (a package name is not a series);
 *  2. view and correct the characters remembered for it — Arabic spelling, aliases, lock.
 *
 * Every read and write happens on [Dispatchers.IO]; the store itself is the same process-wide
 * instance the translation pipeline uses, so a correction is visible to the next capture
 * without any reload (the pipeline rebuilds its session per page).
 *
 * The screen never invents a name: a record only exists because the pipeline detected it or
 * the reader typed it, and unlocking keeps the spelling so it can be corrected again.
 */
@Composable
fun CharacterNamesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { SeriesCatalog(SeriesPrefs(context)) }
    val store = remember { CharacterMemory.store(context) }

    var series by remember { mutableStateOf<List<Series>>(emptyList()) }
    var active by remember { mutableStateOf<Series?>(null) }
    var records by remember { mutableStateOf<List<CharacterRecord>>(emptyList()) }
    var newTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        busy = true
        val (loadedSeries, loadedActive, loadedRecords) = withContext(Dispatchers.IO) {
            val all = runCatching { catalog.all() }.getOrDefault(emptyList())
            val current = runCatching { catalog.active() }.getOrNull()
            val rows = if (current == null) emptyList()
            else runCatching { store.records(current.id) }.getOrDefault(emptyList())
            Triple(all, current, rows)
        }
        series = loadedSeries
        active = loadedActive
        records = loadedRecords
        busy = false
    }

    fun refresh() {
        reloadToken++
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.character_names_back)) }
                Text(
                    text = stringResource(R.string.character_names_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
            Text(
                text = stringResource(R.string.character_names_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ---------------- series ----------------
            Text(
                text = stringResource(R.string.character_names_series),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = active?.title ?: stringResource(R.string.character_names_no_series),
                style = MaterialTheme.typography.bodyMedium,
                color = if (active == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.character_names_new_series)) },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !busy && newTitle.isNotBlank(),
                    onClick = {
                        val title = newTitle
                        busy = true
                        Thread {
                            runCatching {
                                val created = catalog.create(title)
                                catalog.select(created)
                            }
                            newTitle = ""
                            refresh()
                        }.start()
                    }
                ) { Text(stringResource(R.string.character_names_create)) }
            }
            if (series.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in series) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                Thread {
                                    runCatching { catalog.select(s) }
                                    refresh()
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (s.id == active?.id) "✓ ${s.title}" else s.title
                            )
                        }
                    }
                }
            }
            if (active != null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        Thread {
                            runCatching { catalog.select(null) }
                            refresh()
                        }.start()
                    }
                ) { Text(stringResource(R.string.character_names_clear)) }
            }

            HorizontalDivider()

            // ---------------- characters ----------------
            Text(
                text = stringResource(R.string.character_names_characters),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            if (active == null) {
                Text(
                    text = stringResource(R.string.character_names_need_series),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.character_names_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (record in records) {
                    CharacterRow(
                        record = record,
                        store = store,
                        onChanged = { refresh() }
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CharacterRow(
    record: CharacterRecord,
    store: JsonCharacterStore,
    onChanged: () -> Unit
) {
    var arabic by remember(record.id, record.arabicName) { mutableStateOf(record.arabicName) }
    var alias by remember(record.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = record.originalName,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "${record.script.name.lowercase()} · ${record.source.name.lowercase()}" +
                if (record.locked) " · ${stringResource(R.string.character_names_locked)}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = arabic,
                onValueChange = { arabic = it },
                singleLine = true,
                label = { Text(stringResource(R.string.character_names_arabic_hint)) },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val value = arabic
                    Thread {
                        runCatching { store.lock(record.id, value, System.currentTimeMillis()) }
                        onChanged()
                    }.start()
                }
            ) { Text(stringResource(R.string.character_names_save)) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                singleLine = true,
                label = { Text(stringResource(R.string.character_names_alias_hint)) },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val value = alias
                    Thread {
                        runCatching { store.addAlias(record.id, value, System.currentTimeMillis()) }
                        alias = ""
                        onChanged()
                    }.start()
                }
            ) { Text(stringResource(R.string.character_names_add_alias)) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.character_names_lock))
            Switch(
                checked = record.locked,
                onCheckedChange = { locked ->
                    Thread {
                        runCatching {
                            val now = System.currentTimeMillis()
                            if (locked) store.lock(record.id, arabic, now)
                            else store.unlock(record.id, now)
                        }
                        onChanged()
                    }.start()
                }
            )
            TextButton(
                onClick = {
                    Thread {
                        runCatching { store.delete(record.id) }
                        onChanged()
                    }.start()
                }
            ) { Text(stringResource(R.string.character_names_delete)) }
        }
        if (record.aliases.isNotEmpty()) {
            Text(
                text = record.aliases.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
    }
}
