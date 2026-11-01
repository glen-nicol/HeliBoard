// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
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
    var touchCount by remember { mutableFloatStateOf(prefs.getInt(Settings.PREF_SHOW_TOUCH_ELLIPSE_COUNT, Defaults.PREF_SHOW_TOUCH_ELLIPSE_COUNT).toFloat()) }
    var testText by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        MainKeyboardView.setShowRegionsForSettings(true)
        onDispose { MainKeyboardView.setShowRegionsForSettings(false) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.adaptive_key_regions_title),
        settings = emptyList(),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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

            PreferenceCategory(stringResource(R.string.adaptive_key_regions_settings), Modifier.padding(top = 8.dp))
            SliderRow(
                stringResource(R.string.key_baseline_hitbox), stringResource(R.string.key_baseline_hitbox_summary),
                baseline, 100f..BASELINE_RANGE_END, enabled, { "${it.roundToInt()}%" },
                onChange = {
                    if (it != baseline) prefs.edit { putInt(Settings.PREF_KEY_BASELINE_HITBOX, it.roundToInt()) }
                    baseline = it
                },
            )
            SliderRow(
                stringResource(R.string.predictive_key_hitbox), stringResource(R.string.predictive_key_hitbox_summary),
                predicted, 100f..PREDICTED_RANGE_END, enabled, { "${it.roundToInt()}%" },
                onChange = {
                    if (it != predicted) prefs.edit { putInt(Settings.PREF_PREDICTIVE_KEY_HITBOX, it.roundToInt()) }
                    predicted = it
                },
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
                onChange = {
                    if (it != minProbability) prefs.edit { putInt(Settings.PREF_PREDICTIVE_KEY_MIN_PROBABILITY, it.roundToInt()) }
                    minProbability = it
                },
            )
            SliderRow(
                stringResource(R.string.show_touch_ellipse), null,
                touchCount, 0f..10f, true, { it.roundToInt().toString() },
                onChange = {
                    if (it != touchCount) prefs.edit { putInt(Settings.PREF_SHOW_TOUCH_ELLIPSE_COUNT, it.roundToInt()) }
                    touchCount = it
                },
            )
        }
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text(stringResource(R.string.adaptive_key_regions_try_it)) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focusRequester),
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
    description: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyLarge)
        }
        if (description != null)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = range,
            enabled = enabled,
        )
    }
}
