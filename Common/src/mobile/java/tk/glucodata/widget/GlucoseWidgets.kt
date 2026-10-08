package tk.glucodata.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import tk.glucodata.GlucoseUpdateBroadcaster
import tk.glucodata.GlucoseWidget
import tk.glucodata.Log
import tk.glucodata.NotificationChartDrawer
import tk.glucodata.Notify
import tk.glucodata.NumberView

/**
 * Draws both home-screen widgets as plain RemoteViews.
 *
 * Every update renders in one pass on a short-lived background thread: the data
 * is loaded once for all placed widgets, each widget is fitted to its own size and
 * settings, and a widget whose content did not change is not sent again. Nothing
 * runs while no widget is placed, and nothing renders while the screen is off: the
 * widgets are marked dirty instead and drawn once at screen-on ([WidgetRefreshGate]).
 * The loss alarm (MobileGlucoseAlarms) switches them to their stale look, so no
 * timer of their own is needed.
 */
object GlucoseWidgets {
    private const val LOG_ID = "GlucoseWidgets"

    data class Target(val kind: WidgetKind, val appWidgetId: Int)

    private class Entry(val target: Target, val options: WidgetOptions, val size: WidgetSizeDp) {
        val chartWindowMillis: Long
            get() = if (target.kind.offersChart && options.showChart) options.chartWindowMillis else 0L
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var placed = false
    private var screenReceiverRegistered = false

    private val renderQueued = AtomicBoolean(false)
    private val gate = WidgetRefreshGate(Notify.glucosetimeout)

    // One thread, gone after 30 idle seconds: renders never overlap, and the
    // state below is only touched from it.
    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "GlucoseWidgets") }.apply { allowCoreThreadTimeOut(true) }

    private val cache = WidgetRenderCache<WidgetRenderModel, Bitmap>()
    private val chartContexts = HashMap<Boolean, Context>()
    // Set by redrawAll; the next render forgets every widget first.
    private val redrawAllPending = AtomicBoolean(false)

    /** At process start (MobileVariantBootstrap). */
    @JvmStatic
    fun start(context: Context) {
        ensureStarted(context)
    }

    /** The loss alarm found no new reading in time: show the stale look now, or at the next screen-on. */
    @JvmStatic
    fun onReadingStale(context: Context) {
        val app = ensureStarted(context)
        if (placed) requestRender(app)
    }

    /** The host needs these widgets drawn (placed, resized, after a boot or an update), whatever the screen. */
    fun renderNow(context: Context, kind: WidgetKind, appWidgetIds: IntArray, done: (() -> Unit)? = null) {
        val app = ensureStarted(context)
        refreshPlacement(app)
        val targets = appWidgetIds.map { Target(kind, it) }
        executor.execute {
            try {
                // The host may have dropped what it had, or a setting the chart is drawn
                // with changed: send even an unchanged widget, its chart drawn again.
                targets.forEach { cache.forget(it.appWidgetId) }
                render(app, targets)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "renderNow", th)
            } finally {
                done?.invoke()
            }
        }
    }

    /** A widget's settings changed. */
    fun applyOptions(context: Context, appWidgetId: Int) {
        val kind = kindOf(context, appWidgetId) ?: return
        renderNow(context, kind, intArrayOf(appWidgetId))
    }

    /**
     * An app setting the widgets are drawn with changed: every placed widget is drawn
     * again, charts included, now or at the next screen-on.
     */
    @JvmStatic
    fun redrawAll(context: Context) {
        val app = ensureStarted(context)
        if (!placed) return
        redrawAllPending.set(true)
        requestRender(app)
    }

    fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val app = ensureStarted(context)
        store(app).delete(appWidgetIds)
        executor.execute {
            for (id in appWidgetIds) cache.forget(id)
        }
        refreshPlacement(app)
    }

    fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        store(ensureStarted(context)).move(oldIds, newIds)
    }

    /** The first widget of a kind was placed, or the last removed. */
    fun onPlacementChanged(context: Context) {
        refreshPlacement(ensureStarted(context))
    }

    fun store(context: Context) =
        WidgetOptionsStore(context.getSharedPreferences(WidgetOptionsStore.FILE, Context.MODE_PRIVATE))

    fun placedWidgets(context: Context): List<Target> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        return WidgetKind.entries.flatMap { kind ->
            manager.getAppWidgetIds(ComponentName(context, providerClass(kind))).map { Target(kind, it) }
        }
    }

    fun kindOf(context: Context, appWidgetId: Int): WidgetKind? {
        val provider = AppWidgetManager.getInstance(context)?.getAppWidgetInfo(appWidgetId)?.provider ?: return null
        if (provider.packageName != context.packageName) return null
        return WidgetKind.entries.firstOrNull { providerClass(it).name == provider.className }
    }

    fun sizeOf(context: Context, appWidgetId: Int, kind: WidgetKind): WidgetSizeDp {
        val manager = AppWidgetManager.getInstance(context) ?: return kind.fallbackSize
        val options = manager.getAppWidgetOptions(appWidgetId)
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        return WidgetSizing.current(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
            portrait,
            kind.fallbackSize,
        )
    }

    private fun providerClass(kind: WidgetKind): Class<*> = when (kind) {
        WidgetKind.VALUE -> GlucoseWidget::class.java
        WidgetKind.CHART -> ExpressiveWidgetReceiver::class.java
    }

    /**
     * What [kind] with [options] shows at each of [sizes], from [data], for the
     * settings screen's preview: the same presenter and renderer as the widgets.
     * Not on the main thread.
     */
    fun previews(
        context: Context,
        kind: WidgetKind,
        options: WidgetOptions,
        sizes: List<WidgetSizeDp>,
        data: WidgetData,
    ): List<RemoteViews> {
        val fonts = WidgetFonts.resolve(context)
        val renderer = WidgetRenderer(context, fonts)
        val night = WidgetPresenter.isNight(context)
        val largeArrow = largeArrow(context)
        val contexts = HashMap<Boolean, Context>()
        return sizes.map { size ->
            val model = WidgetPresenter.present(context, kind, options, size, data, fonts, night, largeArrow, ::timeText)
            renderer.build(model, model.chartKey?.let { drawChart(context, it, data, contexts) })
        }
    }

    private fun ensureStarted(context: Context): Context {
        appContext?.let { return it }
        synchronized(this) {
            appContext?.let { return it }
            val app = context.applicationContext ?: context
            appContext = app
            GlucoseUpdateBroadcaster.setListener { onGlucoseUpdate() }
            configurationWatcher.remember(app.resources.configuration)
            app.registerComponentCallbacks(configurationWatcher)
            refreshPlacement(app)
            if (placed) requestRender(app)
            return app
        }
    }

    private fun onGlucoseUpdate() {
        val context = appContext ?: return
        if (placed) requestRender(context)
    }

    private fun requestRender(context: Context) {
        if (!isInteractive(context)) {
            gate.deferWhileScreenOff()
            return
        }
        if (!renderQueued.compareAndSet(false, true)) return
        executor.execute {
            renderQueued.set(false)
            renderAll(context)
        }
    }

    private fun renderAll(context: Context) {
        // The screen may have gone off while this waited.
        if (!isInteractive(context)) {
            gate.deferWhileScreenOff()
            return
        }
        try {
            val token = gate.beginRender()
            val targets = placedWidgets(context)
            val data = if (targets.isEmpty()) null else render(context, targets)
            gate.renderedAll(token, data?.freshReadingMillis ?: 0L)
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "renderAll", th)
        }
    }

    /** Draws [targets] from one data load. Render thread only. */
    private fun render(context: Context, targets: List<Target>): WidgetData? {
        if (targets.isEmpty()) return null
        val manager = AppWidgetManager.getInstance(context) ?: return null
        // Forgotten here, on the render thread, so no render queued before it can miss it.
        if (redrawAllPending.getAndSet(false)) cache.forgetAll()
        val store = store(context)
        val entries = targets.map {
            Entry(it, store.load(it.appWidgetId, it.kind), sizeOf(context, it.appWidgetId, it.kind))
        }
        val data = WidgetDataLoader.load(
            context,
            historyWindowMillis = entries.maxOf { it.chartWindowMillis },
            withIob = entries.any { it.options.showIob },
        )
        val fonts = WidgetFonts.resolve(context)
        val renderer = WidgetRenderer(context, fonts)
        val night = WidgetPresenter.isNight(context)
        val largeArrow = largeArrow(context)
        for (entry in entries) {
            val id = entry.target.appWidgetId
            try {
                val model = WidgetPresenter.present(
                    context, entry.target.kind, entry.options, entry.size, data, fonts, night, largeArrow, ::timeText,
                )
                if (cache.shows(id, model)) continue
                val chart = model.chartKey?.let { key ->
                    cache.chart(id, key) { drawChart(context, key, data, chartContexts) }
                }
                manager.updateAppWidget(id, renderer.build(model, chart))
                cache.sent(id, model)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "render $id", th)
            }
        }
        return data
    }

    private fun drawChart(
        context: Context,
        key: WidgetChartKey,
        data: WidgetData,
        contexts: HashMap<Boolean, Context>,
    ): Bitmap? {
        val points = data.historySince(data.nowMillis - key.windowMillis)
        if (points.size < 2) return null
        // The chart palette follows the configuration's night mode; draw it for the widget's background instead.
        val chartContext = contexts.getOrPut(key.dark) {
            val config = Configuration(context.resources.configuration)
            val night = if (key.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            context.createConfigurationContext(config)
        }
        return NotificationChartDrawer.drawChart(
            chartContext, points, key.widthPx, key.heightPx, key.isMmol, key.viewMode,
            true, key.hasCalibration, true, key.sensorSerial, key.windowMillis,
        )
    }

    private fun timeText(millis: Long): String = NumberView.minhourstr(millis)

    private fun largeArrow(context: Context): Boolean =
        context.getSharedPreferences(WidgetDataLoader.PREFS, Context.MODE_PRIVATE)
            .getBoolean("notification_large_trend_arrow", false)

    private fun isInteractive(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Throwable) {
        true
    }

    /** Listens for screen-on only while a widget is placed. */
    private fun refreshPlacement(context: Context) {
        val any = try {
            placedWidgets(context).isNotEmpty()
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "refreshPlacement", th)
            placed
        }
        placed = any
        synchronized(this) {
            if (any && !screenReceiverRegistered) {
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
                filter.addAction(Intent.ACTION_USER_PRESENT)
                ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                screenReceiverRegistered = true
            } else if (!any && screenReceiverRegistered) {
                try {
                    context.unregisterReceiver(screenReceiver)
                } catch (_: IllegalArgumentException) {
                }
                screenReceiverRegistered = false
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val app = appContext ?: return
            if (placed && gate.needsRenderOnScreenOn(System.currentTimeMillis())) requestRender(app)
        }
    }

    /** Night mode, rotation (portrait and landscape sizes differ), locale and density change what is drawn. */
    private val configurationWatcher = object : ComponentCallbacks {
        @Volatile
        private var lastKey: String? = null

        fun remember(config: Configuration) {
            lastKey = key(config)
        }

        private fun key(config: Configuration): String =
            "${config.uiMode and Configuration.UI_MODE_NIGHT_MASK}/${config.orientation}/" +
                "${config.locales.toLanguageTags()}/${config.densityDpi}/${config.layoutDirection}"

        override fun onConfigurationChanged(newConfig: Configuration) {
            val key = key(newConfig)
            if (key == lastKey) return
            lastKey = key
            // The charts were drawn in the old configuration's contexts.
            executor.execute {
                chartContexts.clear()
                cache.dropCharts()
            }
            val context = appContext ?: return
            if (placed) requestRender(context)
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            executor.execute { cache.dropCharts() }
        }
    }
}
