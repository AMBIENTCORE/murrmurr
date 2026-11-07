package io.github.ambientcore.resonance

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

const val EQUALIZER_PREF = "equalizer_config"
val EQ_FREQUENCIES = listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

data class EqualizerPreset(val name: String, val gains: List<Float>, val preamp: Float = 0f) {
    init {
        require(name.isNotBlank() && name.length <= 60 && !name.contains('\u0000')) { "Enter a preset name of 1-60 characters" }
        require(gains.size == 10 && gains.all { it.isFinite() && it in -12f..12f }) { "EQ needs ten gains between -12 and +12 dB" }
        require(preamp.isFinite() && preamp in -18f..6f) { "Preamp must be between -18 and +6 dB" }
    }
}

val EQ_PRESETS = listOf(
    EqualizerPreset("Flat", List(10) { 0f }),
    EqualizerPreset("VShaped", listOf(4f, 3.5f, 2.5f, 1f, -1f, -2f, -1f, .5f, 1.5f, 2f)),
    EqualizerPreset("Bass boost", listOf(5f, 4f, 3f, 1.5f, .5f, 0f, 0f, 0f, 0f, 0f)),
    EqualizerPreset("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, .5f, 1f, 2f, 3f, 3f)),
    EqualizerPreset("Vocal", listOf(-2f, -2f, -1f, 0f, 1f, 2f, 2f, 1f, 0f, -1f)),
)

data class EqualizerParameters(val enabled: Boolean, val gains: List<Float>, val preamp: Float, val peakProtection: Boolean) {
    init { EqualizerPreset("Parameters", gains, preamp) }
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    val gains: List<Float> = EQ_PRESETS.first().gains,
    val preamp: Float = 0f,
    val peakProtection: Boolean = true,
    val customPresets: List<EqualizerPreset> = emptyList(),
) {
    init {
        EqualizerPreset(preset, gains, preamp)
        require(customPresets.size <= 32) { "At most 32 custom EQ presets are supported" }
        val names = customPresets.map { it.name.lowercase(Locale.ROOT) }
        require(names.distinct().size == names.size && customPresets.none { profile ->
            profile.name.equals("Custom", true) || EQ_PRESETS.any { it.name.equals(profile.name, true) }
        }) { "Custom preset names must be unique and must not replace built-in presets" }
        require(preset == "Custom" || presets().any { it.name == preset && it.gains == gains && it.preamp == preamp }) {
            "Preset does not match the saved EQ controls"
        }
    }
    fun presets(): List<EqualizerPreset> = EQ_PRESETS + customPresets
    fun parameters(): EqualizerParameters = EqualizerParameters(enabled, gains, preamp, peakProtection)
    fun select(name: String): EqualizerSettings {
        val profile = presets().firstOrNull { it.name == name } ?: error("EQ preset not found")
        return copy(preset = profile.name, gains = profile.gains, preamp = profile.preamp)
    }
    fun savePreset(name: String): EqualizerSettings {
        val profile = EqualizerPreset(name.trim(), gains.toList(), preamp)
        require(!profile.name.equals("Custom", true) && EQ_PRESETS.none { it.name.equals(profile.name, true) }) { "Choose a different custom preset name" }
        return copy(preset = profile.name, customPresets = customPresets.filterNot { it.name.equals(profile.name, true) } + profile)
    }
    fun deletePreset(name: String): EqualizerSettings {
        require(customPresets.any { it.name == name }) { "Only custom presets can be deleted" }
        return copy(preset = if (preset == name) "Custom" else preset, customPresets = customPresets.filterNot { it.name == name })
    }
}

fun readEqualizer(prefs: SharedPreferences): EqualizerSettings {
    val text = prefs.getString(EQUALIZER_PREF, null) ?: return EqualizerSettings()
    val json = JSONObject(text)
    val version = json.getInt("version")
    require(version in 1..2) { "Unsupported equalizer settings version" }
    val protection = if (version == 1) {
        // Disabling the old gain cut did not mean opting out of peak protection.
        json.getBoolean("autoHeadroom")
        true
    } else json.getBoolean("peakProtection")
    fun gains(item: JSONObject): List<Float> {
        val values = item.getJSONArray("gains")
        return (0 until values.length()).map { values.getDouble(it).toFloat() }
    }
    return EqualizerSettings(json.getBoolean("enabled"), json.getString("preset"), gains(json),
        json.getDouble("preamp").toFloat(), protection,
        json.getJSONArray("customPresets").objects().map { EqualizerPreset(it.getString("name"), gains(it), it.getDouble("preamp").toFloat()) })
}

fun writeEqualizer(prefs: SharedPreferences, settings: EqualizerSettings) {
    val json = JSONObject().put("version", 2).put("enabled", settings.enabled).put("preset", settings.preset)
        .put("gains", JSONArray(settings.gains)).put("preamp", settings.preamp).put("peakProtection", settings.peakProtection)
        .put("customPresets", JSONArray(settings.customPresets.map {
            JSONObject().put("name", it.name).put("gains", JSONArray(it.gains)).put("preamp", it.preamp)
        }))
    prefs.edit().putString(EQUALIZER_PREF, json.toString()).apply()
}
