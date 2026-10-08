@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.widget

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem

/** One preview: what the widget would show at [size]. */
class WidgetPreview(val size: WidgetSizeDp, val views: RemoteViews)

private val horizontalPadding = 16.dp

// Stands in for a wallpaper, so a widget without a background still shows.
private val wallpaper = Brush.linearGradient(listOf(Color(0xFF34466E), Color(0xFF6E4A66)))

private fun labelFor(kind: WidgetKind): Int = when (kind) {
    WidgetKind.VALUE -> R.string.widget_value_label
    WidgetKind.CHART -> R.string.widget_chart_label
}

@Composable
private fun WidgetScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

@Composable
fun WidgetConfigScreen(
    kind: WidgetKind,
    options: WidgetOptions,
    previews: List<WidgetPreview>,
    onOptionsChange: (WidgetOptions) -> Unit,
    onDone: () -> Unit,
) {
    WidgetScaffold(title = stringResource(labelFor(kind)), onBack = onDone) {
        SectionLabel(
            stringResource(R.string.preview),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        PreviewRow(previews)

        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.widget_range_colors),
                subtitle = stringResource(R.string.widget_range_colors_desc),
                checked = options.rangeColors,
                onCheckedChange = { onOptionsChange(options.copy(rangeColors = it)) },
                position = CardPosition.TOP,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = options.showArrow,
                onCheckedChange = { onOptionsChange(options.copy(showArrow = it)) },
                position = CardPosition.MIDDLE,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.widget_delta),
                checked = options.showDelta,
                onCheckedChange = { onOptionsChange(options.copy(showDelta = it)) },
                position = CardPosition.MIDDLE,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.widget_reading_time),
                checked = options.showTime,
                onCheckedChange = { onOptionsChange(options.copy(showTime = it)) },
                position = CardPosition.MIDDLE,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_iob_title),
                checked = options.showIob,
                onCheckedChange = { onOptionsChange(options.copy(showIob = it)) },
                position = if (kind.offersChart) CardPosition.MIDDLE else CardPosition.BOTTOM,
            )
            if (kind.offersChart) {
                SettingsSwitchItem(
                    title = stringResource(R.string.widget_chart_when_tall),
                    checked = options.showChart,
                    onCheckedChange = { onOptionsChange(options.copy(showChart = it)) },
                    position = CardPosition.BOTTOM,
                )
            }
        }

        if (kind.offersChart && options.showChart) {
            SectionLabel(
                stringResource(R.string.widget_chart_range),
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
            ChoiceChips(
                choices = WidgetOptions.CHART_HOURS.map { it to stringResource(R.string.widget_hours, it) },
                selected = options.chartHours,
                onSelect = { onOptionsChange(options.copy(chartHours = it)) },
            )
        }

        SectionLabel(
            stringResource(R.string.background),
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        ChoiceChips(
            choices = listOf(
                WidgetBackground.NONE to stringResource(R.string.widget_background_none),
                WidgetBackground.DARK to stringResource(R.string.theme_dark),
                WidgetBackground.LIGHT to stringResource(R.string.theme_light),
                WidgetBackground.THEME to stringResource(R.string.theme_title),
            ),
            selected = options.background,
            onSelect = { onOptionsChange(options.copy(background = it)) },
        )
        if (options.background != WidgetBackground.NONE) {
            LabelledSlider(
                label = stringResource(R.string.opacity_percent, options.opacityPercent),
                value = options.opacityPercent.toFloat(),
                range = 0f..100f,
                steps = 19,
                onValueChange = { onOptionsChange(options.copy(opacityPercent = it.roundToInt())) },
            )
        }
        LabelledSlider(
            label = stringResource(R.string.text_size_percent, (options.textScale * 100f).roundToInt()),
            value = options.textScale,
            range = WidgetOptions.MIN_TEXT_SCALE..WidgetOptions.MAX_TEXT_SCALE,
            steps = 9,
            onValueChange = { onOptionsChange(options.copy(textScale = (it * 20f).roundToInt() / 20f)) },
        )
    }
}

@Composable
private fun PreviewRow(previews: List<WidgetPreview>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        previews.forEach { preview ->
            key(preview.size) {
                Column {
                    Box(
                        modifier = Modifier
                            .size(preview.size.width.dp, preview.size.height.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(wallpaper),
                    ) {
                        AndroidView(
                            // A plain device theme, like a launcher's, rather than this app's.
                            factory = { context -> FrameLayout(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)) },
                            update = { frame ->
                                frame.removeAllViews()
                                try {
                                    frame.addView(
                                        preview.views.apply(frame.context, frame),
                                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                                    )
                                } catch (_: RuntimeException) {
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        stringResource(R.string.widget_size, preview.size.width, preview.size.height),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

/**
 * Settings, Widgets: every placed widget with its kind and size, each opening
 * [WidgetConfigActivity]. The way in for hosts that cannot reconfigure a widget.
 */
@Composable
fun WidgetListScreen(navController: NavController) {
    val context = LocalContext.current
    var widgets by remember { mutableStateOf(placedWithSizes(context)) }
    // Widgets come and go while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { widgets = placedWithSizes(context) }

    WidgetScaffold(title = stringResource(R.string.widgets_title), onBack = { navController.popBackStack() }) {
        if (widgets.isEmpty()) {
            Text(
                stringResource(R.string.widgets_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
            )
            return@WidgetScaffold
        }
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            widgets.forEachIndexed { index, (target, size) ->
                SettingsItem(
                    title = stringResource(labelFor(target.kind)),
                    subtitle = stringResource(R.string.widget_size, size.width, size.height),
                    onClick = { context.startActivity(WidgetConfigActivity.intent(context, target.appWidgetId)) },
                    trailingContent = {
                        Text(
                            stringResource(R.string.widget_customize),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    position = when {
                        widgets.size == 1 -> CardPosition.SINGLE
                        index == 0 -> CardPosition.TOP
                        index == widgets.lastIndex -> CardPosition.BOTTOM
                        else -> CardPosition.MIDDLE
                    },
                )
            }
        }
    }
}

private fun placedWithSizes(context: android.content.Context): List<Pair<GlucoseWidgets.Target, WidgetSizeDp>> =
    try {
        GlucoseWidgets.placedWidgets(context).map { it to GlucoseWidgets.sizeOf(context, it.appWidgetId, it.kind) }
    } catch (_: RuntimeException) {
        emptyList()
    }
