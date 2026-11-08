package io.github.ambientcore.resonance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

private fun eqDb(value: Float): String = "%+.1f dB".format(Locale.ROOT, value)
private fun eqFrequency(index: Int): String = EQ_FREQUENCIES[index].let {
    if (it >= 1000) "${(it / 1000).toInt()} kHz" else "${it.toInt()} Hz"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerPanel(model: MusicViewModel, dismiss: () -> Unit) {
    val audio by model.audio.collectAsStateWithLifecycle()
    val settings = audio.equalizer
    val rate = audio.outputSampleRate.takeIf { it > 0 } ?: 48_000
    val curve = remember(settings.gains, settings.preamp, settings.peakProtection, rate) {
        EqualizerCurve(settings.parameters().copy(enabled = true), rate)
    }
    var menu by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("equalizer-panel")) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer", Modifier.weight(1f), fontSize = 24.sp)
                Switch(settings.enabled, { model.editEqualizer { current -> current.copy(enabled = it) } },
                    modifier = Modifier.testTag("equalizer-enabled"))
                IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close equalizer") }
            }
            LazyColumn(Modifier.weight(1f).testTag("equalizer-controls"), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("equalizer-presets")) {
                                Text("Preset: ${settings.preset}", Modifier.weight(1f))
                                Icon(Icons.Rounded.KeyboardArrowDown, null)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                settings.presets().forEach { preset ->
                                    DropdownMenuItem(text = { Text(preset.name) },
                                        modifier = Modifier.testTag("eq-preset-${preset.name}"),
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (settings.preset == preset.name) Icon(Icons.Rounded.Check, null, tint = Lime)
                                                if (preset in settings.customPresets) IconButton(onClick = { menu = false; delete = preset.name }) {
                                                    Icon(Icons.Rounded.DeleteOutline, "Delete EQ preset: ${preset.name}")
                                                }
                                            }
                                        },
                                        onClick = { model.editEqualizer { it.select(preset.name) }; menu = false })
                                }
                            }
                        }
                        TextButton(onClick = { save = true }, modifier = Modifier.testTag("eq-save-preset")) { Text("Save") }
                    }
                    Text(if (settings.enabled) "EQ enabled" else "EQ bypassed", color = if (settings.enabled) Lime else Muted, fontSize = 12.sp)
                    Text("Frequency response", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    EqualizerGraph(curve, settings.enabled)
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (step in 0..3) {
                            val frequency = 20.0 * (minOf(20_000.0, rate * .49) / 20).pow(step / 3.0)
                            val label = if (frequency >= 1000) "%.1f kHz".format(Locale.ROOT, frequency / 1000).replace(".0 ", " ")
                                else "%.0f Hz".format(Locale.ROOT, frequency)
                            Text(label, color = Muted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Preamp", fontSize = 14.sp)
                    EqSlider(settings.preamp, "Preamp", "eq-preamp", -18f..6f) { gain ->
                        model.editEqualizer { if (it.preamp == gain) it else it.copy(preset = "Custom", preamp = gain) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Peak protection", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(settings.peakProtection, { model.editEqualizer { current -> current.copy(peakProtection = it) } },
                            modifier = Modifier.testTag("eq-peak-protection"))
                    }
                    Text(if (settings.enabled) "Manual preamp: ${eqDb(curve.effectivePreamp.toFloat())}" else "Preamp bypassed",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.testTag("eq-applied-preamp"))
                    Text("Limits boosted peaks only when needed, without a fixed volume cut. Both channels share the same gain. Strong boosts can still compress dynamics; with protection off they can clip. Start with preamp at 0 dB.",
                        color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                }
                EQ_FREQUENCIES.indices.forEach { index -> item(key = index) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(eqFrequency(index), Modifier.width(58.dp), fontSize = 12.sp)
                        EqSlider(settings.gains[index], eqFrequency(index), "eq-band-$index", -12f..12f,
                            modifier = Modifier.weight(1f)) { gain ->
                            model.editEqualizer { current ->
                                if (current.gains[index] == gain) current else current.copy(preset = "Custom",
                                    gains = current.gains.mapIndexed { band, old -> if (band == index) gain else old })
                            }
                        }
                    }
                } }
                item {
                    TextButton(onClick = { model.editEqualizer { it.select("Flat") } }, modifier = Modifier.testTag("eq-reset")) { Text("Reset to Flat") }
                    Text("Ten bands / +/-12 dB / 32-bit float. Bands above the source's Nyquist frequency are not applied. EQ and preset changes do not restart playback.",
                        color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
    if (save) {
        var name by remember { mutableStateOf(if (settings.preset in EQ_PRESETS.map { it.name } || settings.preset == "Custom") "" else settings.preset) }
        AlertDialog(onDismissRequest = { save = false }, title = { Text("Save EQ preset") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Preset name") },
                modifier = Modifier.testTag("eq-preset-name")) },
            confirmButton = { TextButton(onClick = { if (model.editEqualizer { it.savePreset(name) }) save = false }, enabled = name.isNotBlank()) { Text("Save preset") } },
            dismissButton = { TextButton(onClick = { save = false }) { Text("Cancel") } })
    }
    delete?.let { name ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete $name?") },
            text = { Text("Only this custom EQ preset is removed.") },
            confirmButton = { TextButton(onClick = { if (model.editEqualizer { it.deletePreset(name) }) delete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun EqSlider(value: Float, label: String, tag: String, range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, change: (Float) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(value, { change(round(it * 2) / 2) }, valueRange = range, steps = 47,
            modifier = Modifier.weight(1f).testTag(tag).semantics { contentDescription = "$label gain" },
            colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Cyan, inactiveTrackColor = InputSurface,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        Text(eqDb(value), Modifier.width(65.dp), fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun EqualizerGraph(curve: EqualizerCurve, enabled: Boolean) {
    val muted = Muted
    val accent = Lime
    val high = minOf(20_000.0, curve.sampleRate * .49)
    val points = remember(curve) { (0..160).map {
        val frequency = 20.0 * (high / 20.0).pow(it / 160.0)
        it / 160f to curve.response(frequency).coerceIn(-24.0, 24.0).toFloat()
    } }
    Row {
    Column(Modifier.width(28.dp).height(115.dp), verticalArrangement = Arrangement.SpaceBetween) {
        listOf("+24", "0", "-24").forEach { Text(it, color = Muted, fontSize = 9.sp) }
    }
    Canvas(Modifier.weight(1f).height(115.dp).clip(RoundedCornerShape(12.dp)).background(InputSurface)
        .testTag("equalizer-curve").semantics { contentDescription = "Equalizer response, minus 24 to plus 24 decibels" }) {
        for (db in listOf(-12, 0, 12)) {
            val y = size.height * (.5f - db / 48f)
            drawLine(muted.copy(alpha = if (db == 0) .6f else .2f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        EQ_FREQUENCIES.filter { it < high }.forEach { frequency ->
            val x = (log10(frequency / 20) / log10(high / 20)).toFloat() * size.width
            drawLine(muted.copy(alpha = .15f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.first * size.width
            val y = size.height * (.5f - point.second / 48f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (enabled) accent else muted, style = Stroke(2.dp.toPx()))
    }
    }
}
