package tk.glucodata.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

/**
 * One widget's settings, with a live preview drawn by the widgets' own renderer.
 * Opened by the launcher (long-press, Settings; or on placement where the host
 * does not support configuration_optional) and from the app's Widgets settings,
 * for hosts such as lock-screen widget apps that offer no way to reconfigure.
 * Every change is saved at once; the widget is redrawn when the changes settle.
 */
class WidgetConfigActivity : ComponentActivity() {
    private val options = MutableStateFlow<WidgetOptions?>(null)
    private val previews = MutableStateFlow<List<WidgetPreview>>(emptyList())
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @Volatile
    private var unapplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Keep the widget whatever happens here: a host that opens this on placement
        // removes the new widget when the result is a cancel.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        val kind = GlucoseWidgets.kindOf(this, appWidgetId)
        if (kind == null) {
            finish()
            return
        }
        val store = GlucoseWidgets.store(this)
        options.value = store.load(appWidgetId, kind)
        val sizes = previewSizes(kind, GlucoseWidgets.sizeOf(this, appWidgetId, kind))
        val app = applicationContext
        val id = appWidgetId

        lifecycleScope.launch(Dispatchers.Default) {
            val data = previewData(app)
            var first = true
            options.filterNotNull().collectLatest { current ->
                if (!first) {
                    // A slider sends a stream of values; draw once it settles.
                    delay(SETTLE_MILLIS)
                    applyNow()
                }
                first = false
                previews.value = try {
                    GlucoseWidgets.previews(app, kind, current, sizes, data)
                        .zip(sizes) { views, size -> WidgetPreview(size, views) }
                } catch (th: Throwable) {
                    Log.stack(LOG_ID, "previews", th)
                    emptyList()
                }
            }
        }

        val themeMode = savedThemeMode()
        setContent {
            JugglucoTheme(themeMode = themeMode) {
                val current by options.collectAsState()
                val shown by previews.collectAsState()
                current?.let { shownOptions ->
                    WidgetConfigScreen(
                        kind = kind,
                        options = shownOptions,
                        previews = shown,
                        onOptionsChange = { changed ->
                            store.save(id, changed)
                            unapplied = true
                            options.value = changed
                        },
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Left before the last change settled.
        if (unapplied) applyNow()
    }

    private fun applyNow() {
        unapplied = false
        GlucoseWidgets.applyOptions(applicationContext, appWidgetId)
    }

    private fun savedThemeMode(): ThemeMode {
        val saved = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
            .getString("theme_mode", ThemeMode.SYSTEM.name)
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    companion object {
        private const val LOG_ID = "WidgetConfig"
        private const val SETTLE_MILLIS = 250L
        private const val PREVIEW_WINDOW_MILLIS = 6L * 60L * 60L * 1000L

        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        /** The widget's own size first, then a one-row, a wide and a tall one. */
        fun previewSizes(kind: WidgetKind, current: WidgetSizeDp): List<WidgetSizeDp> {
            val samples = when (kind) {
                WidgetKind.VALUE -> listOf(WidgetSizeDp(110, 56), WidgetSizeDp(260, 56), WidgetSizeDp(140, 140))
                WidgetKind.CHART -> listOf(WidgetSizeDp(140, 56), WidgetSizeDp(300, 64), WidgetSizeDp(300, 150))
            }
            return (listOf(current) + samples).distinctBy { (it.width / 24) to (it.height / 24) }
        }

        /** The current reading and the longest chart window; a sample when there is no reading. */
        private fun previewData(context: Context): WidgetData {
            val loaded = try {
                WidgetDataLoader.load(context, PREVIEW_WINDOW_MILLIS, withIob = true)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "previewData", th)
                null
            }
            if (loaded?.reading != null) return loaded
            return WidgetSamples.data(System.currentTimeMillis(), isMmol = Applic.unit == 1)
        }
    }
}
