// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlin.math.roundToInt

private const val BASELINE_RANGE_END = 110f
private const val PREDICTED_RANGE_END = 120f

// example next-letter probabilities after typing "th"
private val previewProbabilities = mapOf('e' to 0.55f, 'a' to 0.2f, 'i' to 0.15f, 'r' to 0.05f, 'o' to 0.05f)

@Composable
fun AdaptiveKeyRegionsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val prefChanged = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    // runs after the keyboard's settings were reloaded, so the shown keyboard picks up the new regions
    LaunchedEffect(prefChanged?.value) { KeyboardSwitcher.getInstance().mainKeyboardView?.refreshKeyRegions() }

    var enabled by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_ADAPTIVE_KEY_REGIONS_ENABLED, Defaults.PREF_ADAPTIVE_KEY_REGIONS_ENABLED)) }
    var baseline by remember { mutableFloatStateOf(prefs.getInt(Settings.PREF_KEY_BASELINE_HITBOX, Defaults.PREF_ADAPTIVE_KEY_REGIONS_KEY_BASELINE_HITBOX_SCALE).toFloat()) }
    var predicted by remember { mutableFloatStateOf(prefs.getInt(Settings.PREF_PREDICTIVE_KEY_HITBOX, Defaults.PREF_ADAPTIVE_KEY_REGIONS_PREDICTIVE_KEY_HITBOX_SCALE).toFloat()) }
    var minProbability by remember { mutableFloatStateOf(prefs.getInt(Settings.PREF_PREDICTIVE_KEY_MIN_PROBABILITY, Defaults.PREF_ADAPTIVE_KEY_REGIONS_PREDICTIVE_KEY_MIN_PROBABILITY).toFloat()) }
    var overlap by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_TOUCH_AREA_HIT_DETECTION, Defaults.PREF_TOUCH_AREA_HIT_DETECTION)) }
    var showOnKeyboard by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_SHOW_PREDICTIVE_KEY_HITBOX, Defaults.PREF_ADAPTIVE_KEY_REGIONS_SHOW_PREDICTIVE_KEY_HITBOX)) }
    var testText by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.adaptive_key_regions_title),
        settings = emptyList(),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                SwitchRow(stringResource(R.string.adaptive_key_regions_enable), null, enabled) {
                    enabled = it
                    prefs.edit { putBoolean(Settings.PREF_ADAPTIVE_KEY_REGIONS_ENABLED, it) }
                }
            }
            Text(
                stringResource(R.string.adaptive_key_regions_short),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
            )
            Row(
                Modifier.fillMaxWidth().clickable { showDetails = !showDetails }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.adaptive_key_regions_how_it_works),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painterResource(R.drawable.ic_arrow_left), null,
                    Modifier.rotate(if (showDetails) 90f else -90f),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(showDetails) {
                Text(
                    stringResource(R.string.adaptive_key_regions_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                )
            }

            PreferenceCategory(stringResource(R.string.adaptive_key_regions_preview), Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.adaptive_key_regions_preview_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            HitRegionPreview(
                baselineGrowth = if (enabled) (baseline - 100) / 100 else 0f,
                predictedGrowth = if (enabled) (predicted - 100) / 100 else 0f,
                minProbability = minProbability / 100,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            PreferenceCategory(stringResource(R.string.adaptive_key_regions_settings))
            SliderRow(
                stringResource(R.string.key_baseline_hitbox), stringResource(R.string.key_baseline_hitbox_summary),
                baseline, 100f..BASELINE_RANGE_END, enabled, { "${it.roundToInt()}%" },
                onChange = { baseline = it },
                onDone = { prefs.edit { putInt(Settings.PREF_KEY_BASELINE_HITBOX, baseline.roundToInt()) } }
            )
            SliderRow(
                stringResource(R.string.predictive_key_hitbox), stringResource(R.string.predictive_key_hitbox_summary),
                predicted, 100f..PREDICTED_RANGE_END, enabled, { "${it.roundToInt()}%" },
                onChange = { predicted = it },
                onDone = { prefs.edit { putInt(Settings.PREF_PREDICTIVE_KEY_HITBOX, predicted.roundToInt()) } }
            )
            SwitchRow(stringResource(R.string.touch_area_hit_detection), stringResource(R.string.touch_area_hit_detection_summary),
                overlap) {
                overlap = it
                prefs.edit { putBoolean(Settings.PREF_TOUCH_AREA_HIT_DETECTION, it) }
            }
            SwitchRow(stringResource(R.string.show_predictive_key_hitbox), stringResource(R.string.show_predictive_key_hitbox_summary),
                showOnKeyboard, enabled) {
                showOnKeyboard = it
                prefs.edit { putBoolean(Settings.PREF_SHOW_PREDICTIVE_KEY_HITBOX, it) }
            }
            SliderRow(
                stringResource(R.string.predictive_key_min_probability), stringResource(R.string.predictive_key_min_probability_summary),
                minProbability, 0f..20f, enabled, { "${it.roundToInt()}%" },
                onChange = { minProbability = it },
                onDone = { prefs.edit { putInt(Settings.PREF_PREDICTIVE_KEY_MIN_PROBABILITY, minProbability.roundToInt()) } }
            )
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text(stringResource(R.string.adaptive_key_regions_try_it)) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true, // enables spell check / suggestions
                    imeAction = ImeAction.Done
                )
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyLarge)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
        )
    }
}

private class PreviewKey(val label: String, val hitBox: Rect, val typing: Boolean, val isSpace: Boolean = false)

private fun previewKeys(keyWidth: Float, keyHeight: Float): List<PreviewKey> {
    fun rect(left: Float, row: Int, width: Float) =
        Rect(left.roundToInt(), (row * keyHeight).roundToInt(), (left + width).roundToInt(), ((row + 1) * keyHeight).roundToInt())
    val keys = mutableListOf<PreviewKey>()
    "qwertyuiop".forEachIndexed { i, c -> keys.add(PreviewKey(c.toString(), rect(i * keyWidth, 0, keyWidth), true)) }
    // edge keys of the middle row extend to the keyboard edges, like on the real keyboard
    "asdfghjkl".forEachIndexed { i, c ->
        val left = if (i == 0) 0f else (i + 0.5f) * keyWidth
        val right = if (i == 8) 10 * keyWidth else (i + 1.5f) * keyWidth
        keys.add(PreviewKey(c.toString(), rect(left, 1, right - left), true))
    }
    keys.add(PreviewKey("⇧", rect(0f, 2, 1.5f * keyWidth), false))
    "zxcvbnm".forEachIndexed { i, c -> keys.add(PreviewKey(c.toString(), rect((1.5f + i) * keyWidth, 2, keyWidth), true)) }
    keys.add(PreviewKey("⌫", rect(8.5f * keyWidth, 2, 1.5f * keyWidth), false))
    keys.add(PreviewKey("?123", rect(0f, 3, 1.5f * keyWidth), false))
    keys.add(PreviewKey(",", rect(1.5f * keyWidth, 3, keyWidth), true))
    keys.add(PreviewKey("", rect(2.5f * keyWidth, 3, 5 * keyWidth), true, isSpace = true))
    keys.add(PreviewKey(".", rect(7.5f * keyWidth, 3, keyWidth), true))
    keys.add(PreviewKey("⏎", rect(8.5f * keyWidth, 3, 1.5f * keyWidth), false))
    return keys
}

@Composable
private fun HitRegionPreview(baselineGrowth: Float, predictedGrowth: Float, minProbability: Float, modifier: Modifier) {
    val keyColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val baselineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgb()
    val regionColor = Color(0xFFFF0080)
    val density = LocalDensity.current
    val labelSize = with(density) { 16.sp.toPx() }
    val percentSize = with(density) { 9.sp.toPx() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val dash = remember { DashPathEffect(floatArrayOf(6f, 6f), 0f) }

    Canvas(modifier.aspectRatio(10f / (4 * 1.35f))) {
        val keyWidth = size.width / 10
        val keyHeight = keyWidth * 1.35f
        val keyboardWidth = size.width
        val keyboardHeight = 4 * keyHeight
        val keys = previewKeys(keyWidth, keyHeight)
        val probabilities = keys.map { k -> if (k.label.length == 1) previewProbabilities[k.label[0]] ?: 0f else 0f }

        val totalGrowth = baselineGrowth + predictedGrowth
        val predictedWeights = if (predictedGrowth > 0f) KeyDetector.predictedWeights(
            keys.map { it.hitBox }.toTypedArray(), probabilities.toFloatArray(), predictedGrowth,
            2 * KeyDetector.maxStretchX(totalGrowth, keyWidth), 2 * KeyDetector.maxStretchY(totalGrowth, keyHeight)
        ) else FloatArray(keys.size)
        fun region(key: PreviewKey, weight: Float) =
            KeyDetector.buildRegionPath(key.hitBox, weight, key.isSpace, keyWidth, keyHeight, keyboardWidth, keyboardHeight)

        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = keyColor
            val gap = keyWidth * 0.06f
            keys.forEach { k ->
                c.drawRoundRect(k.hitBox.left + gap, k.hitBox.top + gap, k.hitBox.right - gap, k.hitBox.bottom - gap, gap * 2, gap * 2, paint)
            }

            if (baselineGrowth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = baselineColor
                keys.forEachIndexed { i, k ->
                    if (k.typing && (predictedWeights[i] <= 0f || probabilities[i] < minProbability))
                        c.drawPath(region(k, baselineGrowth), paint)
                }
            }

            keys.forEachIndexed { i, k ->
                if (predictedWeights[i] <= 0f || probabilities[i] < minProbability) return@forEachIndexed
                val path = region(k, baselineGrowth + predictedWeights[i])
                val alpha = 0.15f + 0.35f * (predictedWeights[i] / predictedGrowth).coerceAtMost(1f)
                paint.style = Paint.Style.FILL
                paint.color = regionColor.copy(alpha = alpha).toArgb()
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = regionColor.toArgb()
                c.drawPath(path, paint)
                if (baselineGrowth > 0f) {
                    paint.pathEffect = dash
                    c.drawPath(region(k, baselineGrowth), paint)
                    paint.pathEffect = null
                }
            }

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = labelSize
            paint.color = labelColor
            keys.forEach { k ->
                c.drawText(k.label, k.hitBox.exactCenterX(), k.hitBox.exactCenterY() + labelSize / 3, paint)
            }
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = percentSize
            paint.color = regionColor.toArgb()
            keys.forEachIndexed { i, k ->
                if (predictedWeights[i] <= 0f || probabilities[i] < minProbability) return@forEachIndexed
                c.drawText("${(probabilities[i] * 100).roundToInt()}%", k.hitBox.right - gap * 2, k.hitBox.bottom - gap * 2, paint)
            }
        }
    }
}
