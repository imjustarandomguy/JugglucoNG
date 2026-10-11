package tk.glucodata

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object UiRefreshBus {
    sealed interface Event {
        data object DataChanged : Event
        data object StatusOnly : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _revision = MutableStateFlow(0L)

    val events = _events.asSharedFlow()
    val revision = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1L
    }

    @JvmStatic
    fun requestDataRefresh() {
        requestDataRefresh(0L)
    }

    /**
     * [requestDataRefresh] after storing a reading taken at [readingTimeMillis]: the
     * current reading is worked out again at once, and published as a new one if it is.
     */
    @JvmStatic
    fun requestDataRefresh(readingTimeMillis: Long) {
        bumpRevision()
        CurrentReadingHub.onDisplayInputsChanged(
            if (readingTimeMillis > 0L) DisplayedReading.Reason.NEW_READING else DisplayedReading.Reason.BACKFILL
        )
        _events.tryEmit(Event.DataChanged)
        Notify.scheduleDataChangedRefresh()
        GlucoseUpdateBroadcaster.send(Applic.app)
        refreshWatchFaceSurfaces()
    }

    /**
     * Watch face and complications only refreshed when the watch itself took a
     * BLE reading, so on a companion watch — where readings arrive over the Data
     * Layer instead — the face sat frozen at whatever it last saw.
     *
     * The current reading reaches them through [CurrentReadingHub], at once. This is
     * for what draws history, and the complications fold it: a backfill delivers
     * dozens of chunks, and each would otherwise ask every data source to redraw.
     */
    private fun refreshWatchFaceSurfaces() {
        if (!Applic.isWearable) return
        runCatching { GlucoseValueRefreshAccess.get()?.onStoredDataChanged() }
    }

    @JvmStatic
    fun requestStatusRefresh() {
        _events.tryEmit(Event.StatusOnly)
        runCatching { Floating.invalidatefloat() }
        // The phone ongoing notification reconciles through the same coalesced visual
        // path as data changes (bounded debounce in Notify); status-only work never
        // broadcasts, alarms, or retriggers alertwatch delivery.
        runCatching { Notify.scheduleStatusChangedRefresh() }
    }
}
