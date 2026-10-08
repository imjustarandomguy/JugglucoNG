package tk.glucodata.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Both widgets' provider: the host's calls go to [GlucoseWidgets]. Readings do
 * not come through here; GlucoseWidgets hears of them in-process, so a placed
 * widget costs no broadcast per reading and an unplaced one nothing at all.
 */
abstract class GlucoseWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        GlucoseWidgets.renderNow(context, kind, appWidgetIds) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val pending = goAsync()
        GlucoseWidgets.renderNow(context, kind, intArrayOf(appWidgetId)) { pending.finish() }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        GlucoseWidgets.onDeleted(context, appWidgetIds)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        GlucoseWidgets.onRestored(context, oldWidgetIds, newWidgetIds)
    }

    override fun onEnabled(context: Context) {
        GlucoseWidgets.onPlacementChanged(context)
    }

    override fun onDisabled(context: Context) {
        GlucoseWidgets.onPlacementChanged(context)
    }
}
