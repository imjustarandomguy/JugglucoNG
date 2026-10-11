package tk.glucodata

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration-style JVM harness for the N1 startup subset. Unlike the predicate
 * tests, this compiles the actual Notify foreground-startup/restore/fornotify
 * method bodies and the ongoing notification's refresh lifecycle (extracted from
 * Notify.java) against recording stub Android types, with a controllable worker
 * queue and blockable current-reading/render fakes.
 *
 * Covered production wiring: placeholder-first promotion before the current reading
 * is awaited, ticket invalidation by any genuine fornotify publication, superseded
 * epochs, stop/destroy invalidation, Wear synchronous path, the current reading
 * passed to the renderer, honest stale/no-value/waiting/no-sensor looks, the
 * current reading's publish driving the notification (no deadline of its own),
 * alertwatch delivery only for a new reading, and header-timestamp application.
 */
class NotificationStartupRestoreTests {
    private fun harness(): Any {
        val h = compiled.getConstructor().newInstance()
        setStaticOwner("GlucoseUpdateBroadcaster", h)
        setStaticOwner("OngoingNotificationAccess", h)
        setStaticOwner("Service", h)
        setStaticOwner("Log", h)
        setStaticOwner("DisplayedReading", h)
        call(h, "resetStatics")
        call(h, "openGate", "resolver")
        call(h, "openGate", "render")
        return h
    }

    private fun setStaticOwner(nested: String, h: Any) {
        val cls = compiled.declaredClasses.first { it.simpleName == nested }
        val f = cls.getField("owner")
        f.isAccessible = true
        f.set(null, h)
    }

    private fun get(h: Any, name: String): Any? {
        val f = h.javaClass.getField(name)
        return f.get(h)
    }

    private fun set(h: Any, name: String, value: Any?) {
        h.javaClass.getField(name).set(h, value)
    }

    private fun call(h: Any, name: String, vararg args: Any?): Any? {
        val m = h.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
        return m.invoke(h, *args)
    }

    private fun staticCall(name: String, vararg args: Any?): Any? {
        val m = compiled.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(null, *args)
    }

    private fun nested(name: String): Class<*> =
        compiled.declaredClasses.first { it.simpleName == name }

    private fun nestNew(nested: String, vararg args: Pair<Class<*>?, Any?>): Any {
        val cls = nested(nested)
        return nestNewIn(cls, *args)
    }

    private fun nestNewIn(cls: Class<*>, vararg args: Pair<Class<*>?, Any?>): Any {
        val ctor = cls.getDeclaredConstructor(*args.map { it.first!! }.toTypedArray())
        ctor.isAccessible = true
        return ctor.newInstance(*args.map { it.second }.toTypedArray())
    }

    private fun nestField(obj: Any, field: String): java.lang.reflect.Field {
        val f = obj.javaClass.getField(field)
        f.isAccessible = true
        return f
    }

    private fun newService(h: Any): Any = nestNew("Service")

    private fun serviceCalls(svc: Any): List<*> {
        @Suppress("UNCHECKED_CAST")
        return nestField(svc, "calls").get(svc) as List<*>
    }

    private fun events(h: Any): List<*> {
        @Suppress("UNCHECKED_CAST")
        return get(h, "events") as List<*>
    }

    private fun queued(h: Any): Int = (get(h, "glucoseRefreshHandler") as Any).let {
        val m = it.javaClass.getDeclaredMethod("queued")
        m.isAccessible = true
        m.invoke(it) as Int
    }

    private fun drainOne(h: Any, timeoutMs: Long = 5000): Boolean {
        val handler = get(h, "glucoseRefreshHandler")!!
        val take = handler.javaClass.getDeclaredMethod("takeNext", Long::class.javaPrimitiveType)
        take.isAccessible = true
        val r = take.invoke(handler, timeoutMs) as Runnable?
        if (r == null) return false
        r.run()
        return true
    }

    private fun poll(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    /** The hub's current reading as the stub carries it: [freshness] is one of DisplayedReading.Freshness. */
    private fun readingAt(time: Long, value: Float = 100f, freshness: String = "FRESH", reason: String = "NEW_READING"): Any {
        val cls = nested("DisplayedReading")
        val freshnessType = cls.declaredClasses.first { it.simpleName == "Freshness" }
        val reasonType = cls.declaredClasses.first { it.simpleName == "Reason" }
        @Suppress("UNCHECKED_CAST")
        fun enumOf(type: Class<*>, name: String) = java.lang.Enum.valueOf(type as Class<out Enum<*>>, name)
        return nestNewIn(cls,
            Long::class.javaPrimitiveType to time,
            Float::class.javaPrimitiveType to value,
            freshnessType to enumOf(freshnessType, freshness),
            reasonType to enumOf(reasonType, reason))
    }

    private fun foregroundno(h: Any, svc: Any) {
        call(h, "setCurrentService", svc)
        h.javaClass.getMethod("foregroundno", nested("Service")).invoke(h, svc)
    }

    private fun fornotify(h: Any, notif: Any) {
        h.javaClass.getMethod("fornotify", nested("Notification")).invoke(h, notif)
    }

    private fun newNotification(h: Any): Any = nestNew("Notification")

    private fun lastServiceNotification(svc: Any): Any? =
        nestField(svc, "last").get(svc)

    private fun titleOf(notif: Any?): String? =
        if (notif == null) null else nestField(notif, "title").get(notif) as String?

    private fun boolOf(notif: Any?, field: String): Boolean =
        nestField(notif!!, field).getBoolean(notif)

    private fun whenOf(notif: Any?): Long =
        nestField(notif!!, "when").getLong(notif)

    private fun renderField(h: Any, name: String): Any? {
        val render = get(h, "lastRender")!!
        return render.javaClass.getField(name).also { it.isAccessible = true }.get(render)
    }

    @Test fun promotionPrecedesTheAwaitedCurrentReading() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 60_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        // Placeholder published synchronously with truthful loading state.
        assertEquals(listOf("fg:81431"), serviceCalls(svc))
        val placeholder = lastServiceNotification(svc)
        assertEquals("Loading...", titleOf(placeholder))
        assertFalse(boolOf(placeholder, "showWhen"))
        assertTrue(boolOf(placeholder, "ongoing"))
        assertEquals(1, queued(h))
        // The restore waits for the hub's first reading: no second publish meanwhile.
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        Thread.sleep(100)
        assertEquals("no publish while the current reading is awaited", 1, serviceCalls(svc).size)
        // Ordering: promotion event strictly before the wait.
        val order = events(h)
        assertTrue(order.indexOf("fg:81431") < order.indexOf("resolve"))
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        // Restore rendered the current reading through the quiet visual path.
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        assertEquals(listOf("fg:81431", "resolve", "render", "fg:81431"), events(h))
        assertEquals(0, get(h, "broadcastSends") as Int)
    }

    @Test fun freshRestoreRendersTheCurrentReading() {
        val h = harness()
        val now = System.currentTimeMillis()
        val readingTime = now - 45_000L
        set(h, "cannedReading", readingAt(readingTime, 132f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(132f, renderField(h, "value"))
        assertEquals(readingTime, renderField(h, "glucoseTime"))
        assertEquals("a restore never alerts", true, renderField(h, "once"))
        assertEquals(2, serviceCalls(svc).size)
    }

    @Test fun genuinePublicationDuringRenderInvalidatesPendingRestore() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 30_000L))
        call(h, "closeGate", "render")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "renderEntered") as Boolean }
        // A genuine foreground publication lands while the restore renders.
        val genuine = newNotification(h)
        fornotify(h, genuine)
        call(h, "openGate", "render")
        w.join(5000)
        assertFalse(w.isAlive)
        // Placeholder plus genuine post; the pending restore must not post a third time.
        assertEquals(2, serviceCalls(svc).size)
        assertSame(genuine, lastServiceNotification(svc))
        assertEquals("genuine path fans out, restore path does not", 1, get(h, "broadcastSends") as Int)
    }

    @Test fun repeatedStartupSupersedesQueuedRestore() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 20_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        // Second foreground entry while the first restore is still waiting.
        foregroundno(h, svc)
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        // The superseded restore got its reading but never rendered nor published.
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        assertTrue("second epoch restores", drainOne(h))
        assertEquals(1, get(h, "renderCalls") as Int)
        assertEquals(3, serviceCalls(svc).size)
    }

    @Test fun queuedRestoreDoesNotAdoptNewerEpoch() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        foregroundno(h, svc)
        assertEquals(listOf("fg:81431", "fg:81431"), serviceCalls(svc))
        // The first queued restore carries the superseded ticket: it must drop
        // before waiting, never adopt the newer epoch and never publish.
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "resolverCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        assertTrue(drainOne(h))
        assertEquals(1, get(h, "resolverCalls") as Int)
        assertEquals(1, get(h, "renderCalls") as Int)
        assertEquals(3, serviceCalls(svc).size)
    }

    @Test fun stopInvalidationDropsPendingRestore() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L))
        call(h, "closeGate", "resolver")
        val svc = newService(h)
        foregroundno(h, svc)
        val w = thread { drainOne(h) }
        poll(5000) { get(h, "resolverEntered") as Boolean }
        // Exactly what keeprunning.stopper/onDestroy call, under the same gate.
        staticCall("invalidateStartupRestore", svc)
        call(h, "openGate", "resolver")
        w.join(5000)
        assertFalse(w.isAlive)
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals(1, serviceCalls(svc).size)
    }

    @Test fun sameReadingRestoresOnFreshEpochAfterGenuineTraffic() {
        val h = harness()
        val now = System.currentTimeMillis()
        // Genuine traffic first (invalidates nothing pending, fans out once).
        fornotify(h, newNotification(h))
        assertEquals(1, get(h, "broadcastSends") as Int)
        // A new service epoch may restore the same reading again.
        set(h, "cannedReading", readingAt(now - 90_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(2, serviceCalls(svc).size)
        assertEquals(1, get(h, "renderCalls") as Int)
    }

    @Test fun destroyingOldServiceDoesNotCancelReplacementRestore() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L))
        val old = newService(h)
        val replacement = newService(h)
        foregroundno(h, old)
        foregroundno(h, replacement)
        staticCall("invalidateStartupRestore", old)
        assertTrue(drainOne(h))
        assertTrue(drainOne(h))
        assertEquals(1, serviceCalls(old).size)
        assertEquals(2, serviceCalls(replacement).size)
    }

    @Test fun placeholderRestoreAndGenuinePostsSharePublicationGate() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        call(h, "setCurrentService", svc)
        fornotify(h, newNotification(h))
        assertEquals(listOf(true, true, true), nestField(svc, "publicationLocks").get(svc))
        call(h, "setCurrentService", null)
        fornotify(h, newNotification(h))
        val manager = get(h, "notificationManager")!!
        assertTrue(nestField(manager, "publicationHeldLock").getBoolean(manager))
    }

    @Test fun startupRendererUsesTheAwaitedReadingWithoutAskingAgain() {
        val h = harness()
        val readingTime = System.currentTimeMillis() - 20_000L
        set(h, "cannedReading", readingAt(readingTime, 110f))
        set(h, "subsequentReading", readingAt(readingTime - 600_000L, 180f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals("the startup renderer must use the awaited reading", 1, get(h, "resolverCalls"))
        assertEquals(readingTime, get(h, "renderedReadingTime"))
    }

    @Test fun wearKeepsSynchronousPathWithoutPlaceholderOrQueue() {
        val h = harness()
        set(h, "isWearable", true)
        val svc = newService(h)
        foregroundno(h, svc)
        assertEquals(1, get(h, "attachCalls") as Int)
        assertEquals(listOf("fg:81431"), serviceCalls(svc))
        assertEquals(true, boolOf(lastServiceNotification(svc), "startup"))
        assertEquals(0, queued(h))
        assertEquals(false, get(h, "resolverEntered"))
    }

    @Test fun staleReadingYieldsTimestampedStatusWithoutValueRender() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 400_000L, 150f, "STALE"))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals("stale must not render a current value", 0, get(h, "renderCalls") as Int)
        assertEquals(2, serviceCalls(svc).size)
        val status = lastServiceNotification(svc)
        val title = titleOf(status)
        assertTrue("status carries actual time: $title", title!!.startsWith("No new value since "))
        assertTrue(title.length > "No new value since ".length + 3)
        assertTrue(boolOf(status, "showWhen"))
        assertEquals(now - 400_000L, whenOf(status))
        assertEquals("the value is kept, without arrow", "150", nestField(status!!, "text").get(status))
    }

    @Test fun readingTakenDownYieldsTimestampedStatusAlone() {
        val h = harness()
        val now = System.currentTimeMillis()
        set(h, "cannedReading", readingAt(now - 31 * 60_000L, Float.NaN, "NO_VALUE"))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        val status = lastServiceNotification(svc)
        assertTrue(titleOf(status)!!.startsWith("No new value since "))
        assertNull("no value is kept once it is taken down", nestField(status!!, "text").get(status))
    }

    @Test fun awaitedReadingWaitsWithoutInventedValue() {
        val h = harness()
        set(h, "cannedReading", readingAt(0L, Float.NaN, "AWAITING"))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals("Loading...", titleOf(lastServiceNotification(svc)))
    }

    @Test fun absentSensorReportsNoSensor() {
        val h = harness()
        set(h, "cannedReading", readingAt(0L, Float.NaN, "NO_SENSOR"))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertEquals(0, get(h, "renderCalls") as Int)
        assertEquals("No sensors connected", titleOf(lastServiceNotification(svc)))
    }

    @Test fun renderFailureIsLoggedWithPlaceholderKept() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L))
        set(h, "renderThrow", true)
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        assertTrue("failure must be logged, not swallowed", (get(h, "stackCalls") as Int) > 0)
        assertEquals("no status or reading post on failure", 1, serviceCalls(svc).size)
    }

    private fun visualGeneration(): Long {
        val f = compiled.getDeclaredField("foregroundVisualGeneration")
        f.isAccessible = true
        return f.getLong(null)
    }

    private fun deadlineDelays(h: Any): List<Long> {
        val handler = get(h, "glucoseRefreshHandler")!!
        val f = handler.javaClass.getField("delays")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(handler) as List<Long>
    }

    @Test fun freshRestoreArmsNoDeadlineOfItsOwn() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L, 132f))
        val svc = newService(h)
        foregroundno(h, svc)
        assertTrue(drainOne(h))
        // The current reading's clock takes it stale on every surface at once.
        assertEquals(emptyList<Long>(), deadlineDelays(h))
        assertEquals(0, queued(h))
    }

    @Test fun genuinePublicationBumpsVisualGeneration() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 20_000L))
        val svc = newService(h)
        foregroundno(h, svc)
        val afterStartup = visualGeneration()
        fornotify(h, newNotification(h))
        assertTrue("genuine fornotify must invalidate older visual renders",
            visualGeneration() > afterStartup)
    }

    @Test fun headerTimestampApplicationOnRealBuilderCode() {
        val h = harness()
        val builderClass = nested("Notification").declaredClasses.first { it.simpleName == "Builder" }
        val apply = compiled.declaredMethods.first { it.name == "applyReadingHeaderTimestamp" }
        apply.isAccessible = true
        fun newBuilder(): Any {
            val ctor = builderClass.getDeclaredConstructor(Object::class.java, String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(get(h, "app"), "glucoseNotification")
        }
        fun buildOf(builder: Any): Any {
            val m = builderClass.getDeclaredMethod("build")
            m.isAccessible = true
            return m.invoke(builder)
        }
        val readingTime = 1_700_000_000_123L
        val builder = newBuilder()
        apply.invoke(null, builder, readingTime)
        val built = buildOf(builder)
        assertEquals(readingTime, whenOf(built))
        assertTrue(boolOf(built, "showWhen"))
        val emptyBuilt = buildOf(newBuilder().also { apply.invoke(null, it, 0L) })
        assertFalse("no reading hides the header timestamp", boolOf(emptyBuilt, "showWhen"))
        assertEquals(0L, whenOf(emptyBuilt))
    }

    private fun invokePrivate(h: Any, name: String, vararg args: Any?): Any? {
        val method = compiled.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(h, *args)
    }

    @Test fun visualPublicationInvalidatesPendingStartupRestore() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 1000L))
        val service = newService(h)
        foregroundno(h, service)
        val visual = newNotification(h)
        invokePrivate(h, "publishVisualNotification", visualGeneration(), service, visual)
        assertTrue(drainOne(h))
        assertSame("restore cannot overwrite the newer visual", visual, getNested(service, "last"))
        assertEquals("visual update never rebroadcasts a reading", 0, get(h, "broadcastSends"))
    }

    private fun getNested(value: Any, name: String): Any? = value.javaClass.getField(name).also { it.isAccessible = true }.get(value)

    @Test fun startupPublicationInvalidatesAlreadyRenderedVisual() {
        val h = harness()
        val service = newService(h)
        foregroundno(h, service)
        val oldGeneration = visualGeneration()
        assertTrue(drainOne(h))
        val restored = getNested(service, "last")
        invokePrivate(h, "publishVisualNotification", oldGeneration, service, newNotification(h))
        assertSame(restored, getNested(service, "last"))
    }

    @Test fun cancellationBelongsToCurrentServiceAndInvalidatesRunningRender() {
        val h = harness()
        val old = newService(h)
        val replacement = newService(h)
        call(h, "setCurrentService", replacement)
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        val generation = visualGeneration()
        invokePrivate(h, "cancelOngoingNotificationRefreshes", old)
        assertEquals(generation, visualGeneration())
        invokePrivate(h, "cancelOngoingNotificationRefreshes", replacement)
        assertTrue(visualGeneration() > generation)
        invokePrivate(h, "publishVisualNotification", generation, replacement, newNotification(h))
        assertNull(getNested(replacement, "last"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun overdueDataAndStatusCallbacksKeepTheirQueueSlots() {
        val h = harness()
        invokePrivate(h, "scheduleDataChangedNotificationRefresh")
        invokePrivate(h, "scheduleStatusChangedNotificationRefresh")
        set(h, "uptime", 50_000L)
        invokePrivate(h, "scheduleDataChangedNotificationRefresh")
        invokePrivate(h, "scheduleStatusChangedNotificationRefresh")
        assertEquals("overdue callbacks are still owned", 2, call(h, "queuedCount"))
    }

    @Test fun failedVisualPublicationRetriesOnce() {
        val h = harness()
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 1000L))
        set(h, "failPublication", true)
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        assertEquals(1, call(h, "queuedCount"))
        assertTrue(drainOne(h))
        assertEquals("persistent failure cannot spin", 0, call(h, "queuedCount"))
        assertNull(getNested(get(h, "notificationManager")!!, "last"))
    }

    @Test fun aReadingPostOnThePhoneRedrawsTheCurrentReading() {
        val h = harness()
        val time = System.currentTimeMillis() - 1000L
        set(h, "cannedReading", readingAt(time, 123f))
        val reading = nestNew("notGlucose", Long::class.javaPrimitiveType to time)
        invokePrivate(h, "postForegroundGlucoseNotification", -1, 123f, "123", reading, false)
        // Nothing drawn from the arguments: one coalesced redraw of the current reading.
        assertEquals(0, get(h, "renderCalls"))
        assertEquals(1, call(h, "queuedCount"))
        assertTrue(drainOne(h))
        assertEquals(1, get(h, "renderCalls"))
        assertEquals(true, renderField(h, "once"))
        assertEquals(0, get(h, "broadcastSends"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun aStalePublishRedrawsTheReadingInItsStaleLook() {
        val h = harness()
        val time = System.currentTimeMillis() - 1000L
        set(h, "cannedReading", readingAt(time, 140f))
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        // No deadline of its own: the hub's clock publishes the same reading gone stale.
        assertEquals(0, call(h, "queuedCount"))
        set(h, "cannedReading", readingAt(time, 140f, "STALE", "STALE_TICK"))
        staticCall("onReadingPublished", get(h, "cannedReading"))
        // Quietly, through the bounded debounce of data changes.
        assertEquals(listOf(1000L), deadlineDelays(h))
        assertTrue(drainOne(h))
        assertTrue(drainOne(h))
        val manager = get(h, "notificationManager")!!
        val stale = getNested(manager, "last")!!
        assertTrue((getNested(stale, "title") as String).startsWith("No new value"))
        assertEquals(time, getNested(stale, "when"))
        assertEquals(0, get(h, "broadcastSends"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun alertwatchDeliversOnlyANewReadingAsOne() {
        val h = harness()
        val time = System.currentTimeMillis() - 1000L
        set(h, "alertwatch", true)
        set(h, "cannedReading", readingAt(time, 120f))
        staticCall("onReadingPublished", get(h, "cannedReading"))
        // A quiet redraw folded into the same run keeps the delivery.
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        assertEquals("a new reading alerts", false, renderField(h, "once"))
        assertEquals(0, call(h, "queuedCount"))
        staticCall("onReadingPublished", readingAt(time, 120f, "FRESH", "BACKFILL"))
        assertTrue(drainOne(h))
        assertTrue(drainOne(h))
        assertEquals("a backfill does not", true, renderField(h, "once"))
        set(h, "alertwatch", false)
        staticCall("onReadingPublished", readingAt(time + 300_000L, 125f))
        assertTrue(drainOne(h))
        assertEquals("nor anything without alertwatch", true, renderField(h, "once"))
    }

    @Test fun dequeuedCallbacksCannotRescheduleAfterCurrentServiceCancellation() {
        for (method in listOf("scheduleDataChangedNotificationRefresh", "scheduleStatusChangedNotificationRefresh", "scheduleVisualNotificationRefresh")) {
            val h = harness()
            val service = newService(h)
            call(h, "setCurrentService", service)
            invokePrivate(h, method)
            val handler = get(h, "glucoseRefreshHandler")!!
            val take = handler.javaClass.getDeclaredMethod("takeNext", Long::class.javaPrimitiveType).also { it.isAccessible = true }
            val dequeued = take.invoke(handler, 1L) as Runnable
            invokePrivate(h, "cancelOngoingNotificationRefreshes", service)
            call(h, "setCurrentService", null)
            val generation = visualGeneration()
            dequeued.run()
            assertEquals(generation, visualGeneration())
            assertEquals(0, call(h, "queuedCount"))
        }
    }

    @Test fun wearStoredReadingKeepsAlarmIdAndOngoingAdapter() {
        val h = harness()
        set(h, "isWearable", true)
        val time = System.currentTimeMillis() - 1000L
        val reading = nestNew("notGlucose", Long::class.javaPrimitiveType to time)
        invokePrivate(h, "postForegroundGlucoseNotification", -1, 123f, "123", reading, true)
        val manager = get(h, "notificationManager")!!
        assertEquals(listOf("notify:81432"), getNested(manager, "calls"))
        assertEquals(1, get(h, "updateCalls"))
        assertEquals(0, call(h, "queuedCount"))
    }

    @Test fun startupPublicationFailureGetsQuietRetryWithoutNewInput() {
        val h = harness()
        val service = newService(h)
        set(h, "cannedReading", readingAt(System.currentTimeMillis() - 1000L))
        foregroundno(h, service)
        set(h, "failPublication", true)
        assertTrue(drainOne(h))
        assertFalse(boolOf(lastServiceNotification(service), "rendered"))
        set(h, "failPublication", false)
        assertTrue(drainOne(h))
        assertTrue(boolOf(lastServiceNotification(service), "rendered"))
        assertEquals(0, get(h, "broadcastSends"))
    }

    @Test fun noSensorWithoutServiceCancelsInsteadOfPublishingPlaceholder() {
        val h = harness()
        set(h, "cannedReading", readingAt(0L, Float.NaN, "NO_SENSOR"))
        invokePrivate(h, "scheduleVisualNotificationRefresh")
        assertTrue(drainOne(h))
        val manager = get(h, "notificationManager")!!
        assertEquals(listOf("cancel:81431"), getNested(manager, "calls"))
        assertNull(getNested(manager, "last"))
        assertEquals(0, call(h, "queuedCount"))
    }

    companion object {
        private val compiled: Class<*> by lazy {
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
            val source = File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText()
            val regionStart = source.indexOf("    // N1-STARTUP-REGION-BEGIN")
            check(regionStart >= 0) { "startup region begin marker missing" }
            val regionEnd = source.indexOf("    // N1-STARTUP-REGION-END")
            check(regionEnd > regionStart) { "startup region end marker missing" }
            val region = source.substring(regionStart, regionEnd)
            val refreshPolicy = File(root, "Common/src/main/java/tk/glucodata/NotificationRefreshPolicy.java")
                .readText().substringAfter("package tk.glucodata;")
                .replace("final class NotificationRefreshPolicy", "static final class NotificationRefreshPolicy")
            val lifecycle = source.substring(
                source.indexOf("    private final Runnable dataChangedGlucoseRefreshRunnable"),
                source.indexOf("    private static final int MIN_ALERT_DURATION_SECONDS"))
                .replace("final Notify noti", "final NotifyStartupHarness noti")
            val fnSig = "    void fornotify(Notification notif) {"
            val fnStart = source.indexOf(fnSig)
            check(fnStart >= 0) { "fornotify not found" }
            val fnEndAnchor = source.indexOf(
                "    // static final long glucosetimeout=1000*60*3;", fnStart)
            check(fnEndAnchor > fnStart) { "fornotify end anchor missing" }
            val fnClose = source.lastIndexOf("\n    }", fnEndAnchor)
            check(fnClose > fnStart) { "fornotify close brace missing" }
            val fornotifyBody = source.substring(fnStart + fnSig.length, fnClose)
            val dir = java.nio.file.Files.createTempDirectory("notify-startup-restore-test").toFile()
            val file = File(dir, "NotifyStartupHarness.java")
            file.writeText("""
                package tk.glucodata;
                import static java.lang.String.format;
                import java.util.*;
                public class NotifyStartupHarness {
                    public static boolean isWearable = false;
                    public boolean doLog = false;
                    public static String LOG_ID = "test";
                    public static java.util.Locale usedlocale = java.util.Locale.ROOT;
                    public static String glucoseformat = "%.0f";
                    public static java.text.DateFormat timef =
                        new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
                    public static int FOREGROUND_GLUCOSE_NOTIFICATION_KIND = -1;
                    public static String GLUCOSENOTIFICATION = "glucoseNotification";
                    public static long glucosetimeout = 330000L;
                    public static int glucosenotificationid = 81431;
                    public static int glucosealarmid = 81432;
                    public static final int FLAG_ONGOING_EVENT = 2;
                    public static int VISIBILITY_PUBLIC = 1;
                    public TestApp app = new TestApp();
                    public static FakeHandler glucoseRefreshHandler = new FakeHandler();
                    public FakeManager notificationManager = new FakeManager();
                    public int broadcastSends = 0;
                    public int attachCalls = 0;
                    public int updateCalls = 0;
                    public int stackCalls = 0;
                    public List<String> events = Collections.synchronizedList(new ArrayList<>());
                    public String sensorSerial = "sensor-1";
                    public boolean resolverEntered = false;
                    public volatile boolean resolverGateOpen = true;
                    public int resolverCalls = 0;
                    public DisplayedReading cannedReading = null;
                    public DisplayedReading subsequentReading = null;
                    public long renderedReadingTime;
                    public boolean renderEntered = false;
                    public volatile boolean renderGateOpen = true;
                    public boolean renderThrow = false;
                    public int renderCalls = 0;
                    public RenderCall lastRender = null;
                    public boolean startupContentCalled = false;
                    public static NotifyStartupHarness onenot;
                    public static boolean showalways = true, alertwatch = false;
                    public boolean hasvalue;
                    public long pendingDataRefreshAtUptimeMs, pendingStatusRefreshAtUptimeMs;
                    public boolean visualRefreshPending, pendingVisualAlerting;
                    public long pendingVisualGeneration, publicationRetryGeneration;
                    public Service pendingVisualService, publicationRetryService;
                    public static final long DATA_CHANGED_NOTIFICATION_REFRESH_DELAY_MS = 1000L;
                    public static final long FAILED_NOTIFICATION_RETRY_DELAY_MS = 5000L;
                    public static Runnable storedGlucoseRefresh = () -> {};
                    public boolean failPublication;
                    public long uptime = 10_000L;
                    public static class android { public static class os { public static class SystemClock {
                        public static long uptimeMillis() { return onenot.uptime; }
                    } } }
                    public int queuedCount() { return glucoseRefreshHandler.queued(); }
                    private String resolveNotificationSensorSerial() { return sensorSerial; }
                    private String resolveNotificationStatusText(String serial, String fallback) { return fallback; }
                    $refreshPolicy
                    $lifecycle
                    private final Object resolverLock = new Object();
                    private final Object renderLock = new Object();
                    static class R {
                        static class string {
                            static final int loading_data = 1;
                            static final int no_sensors_connected = 2;
                            static final int nonewvalue = 3;
                        }
                        static class drawable { static final int novalue = 10; }
                    }
                    static class Build {
                        static class VERSION { static int SDK_INT = 34; }
                        static class VERSION_CODES { static final int O = 26, LOLLIPOP = 21; }
                    }
                    static class TestApp {
                        String getString(int id) {
                            if (id == R.string.loading_data) return "Loading...";
                            if (id == R.string.no_sensors_connected) return "No sensors connected";
                            return "No new value since ";
                        }
                    }
                    static class Applic {
                        static int unit = 0;
                        static TestApp app = new TestApp();
                        static TestApp getContext() { return app; }
                    }
                    static class Log {
                        public static NotifyStartupHarness owner;
                        static void i(String a, String b) {}
                        static void e(String a, String b) {}
                        static void stack(String a, String b, Throwable t) { owner.stackCalls++; }
                    }
                    static class Service {
                        public static NotifyStartupHarness owner;
                        public List<String> calls = new ArrayList<>();
                        public Notification last;
                        public List<Boolean> publicationLocks = new ArrayList<>();
                        public void startForeground(int id, Notification n) {
                            if (owner.failPublication) throw new IllegalStateException("publisher down");
                            publicationLocks.add(Thread.holdsLock(foregroundPublicationLock));
                            calls.add("fg:" + id);
                            owner.events.add("fg:" + id);
                            last = n;
                        }
                        public void startForeground(int id, Notification n, int type) {
                            calls.add("fg:" + id + ":" + type);
                            owner.events.add("fg:" + id);
                            last = n;
                        }
                    }
                    static class Notification {
                        static final String CATEGORY_SERVICE = "service";
                        public String title;
                        public String text;
                        public boolean showWhen = true;
                        public boolean ongoing = false;
                        public boolean startup = false;
                        public boolean rendered = false;
                        public String category;
                        public long when = 123L;
                        public int flags = 0;
                        static class Builder {
                            Notification n = new Notification();
                            Builder(Object ctx, String type) {}
                            Builder(Object ctx) {}
                            Builder setSmallIcon(int d) { return this; }
                            Builder setOnlyAlertOnce(boolean b) { return this; }
                            Builder setContentTitle(String t) { n.title = t; return this; }
                            Builder setShowWhen(boolean b) { n.showWhen = b; return this; }
                            Builder setOngoing(boolean b) { n.ongoing = b; return this; }
                            Builder setVisibility(int v) { return this; }
                            Builder setCategory(String c) { n.category = c; return this; }
                            Builder setWhen(long w) { n.when = w; return this; }
                            Notification build() { return n; }
                        }
                    }
                    static class FakeHandler {
                        List<Runnable> queue = new ArrayList<>();
                        public List<Long> delays = new ArrayList<>();
                        synchronized void post(Runnable r) { queue.add(r); notifyAll(); }
                        synchronized void postDelayed(Runnable r, long delayMs) {
                            delays.add(delayMs);
                            queue.add(r);
                            notifyAll();
                        }
                        synchronized void removeCallbacks(Runnable r) { queue.remove(r); }
                        synchronized int queued() { return queue.size(); }
                        public synchronized Runnable takeNext(long timeoutMs) throws InterruptedException {
                            long end = System.currentTimeMillis() + timeoutMs;
                            while (queue.isEmpty()) {
                                long w = end - System.currentTimeMillis();
                                if (w <= 0) return null;
                                wait(w);
                            }
                            return queue.remove(0);
                        }
                    }
                    static class FakeManager {
                        public List<String> calls = new ArrayList<>();
                        public Notification last;
                        public boolean publicationHeldLock;
                        void cancel(int id) { calls.add("cancel:" + id); last = null; }
                        void notify(int id, Notification n) {
                            if (onenot.failPublication) throw new IllegalStateException("publisher down");
                            publicationHeldLock = Thread.holdsLock(foregroundPublicationLock);
                            calls.add("notify:" + id); last = n;
                        }
                    }
                    static class GlucoseUpdateBroadcaster {
                        public static NotifyStartupHarness owner;
                        static void send(Object app) { owner.broadcastSends++; }
                    }
                    static class OngoingNotificationAccess {
                        public static NotifyStartupHarness owner;
                        static OngoingNotificationAccess shared = new OngoingNotificationAccess();
                        static OngoingNotificationAccess get() { return shared; }
                        Notification attach(Service s, Notification n, int id) {
                            owner.attachCalls++;
                            return n;
                        }
                        void updateStatus(Object app, int id) { owner.updateCalls++; }
                    }
                    static class keeprunning {
                        static Service theservice = null;
                        static boolean started = false;
                    }
                    /** The hub's current reading, as far as the notification reads it. */
                    static class DisplayedReading {
                        public static NotifyStartupHarness owner;
                        enum Freshness { FRESH, STALE, NO_VALUE, AWAITING, NO_SENSOR }
                        enum Reason { STARTUP, NEW_READING, BACKFILL, STALE_TICK, SETTINGS }
                        public long time;
                        public float value;
                        public Freshness freshness;
                        public Reason reason;
                        DisplayedReading(long t, float v, Freshness f, Reason r) {
                            time = t; value = v; freshness = f; reason = r;
                        }
                        long getTimeMillis() { return time; }
                        float getValue() { return value; }
                        Freshness getFreshness() { return freshness; }
                        Reason getReason() { return reason; }
                        boolean getShowsValue() {
                            return (freshness == Freshness.FRESH || freshness == Freshness.STALE) && value > 0f;
                        }
                        String getSensorId() { return owner.sensorSerial; }
                    }
                    static class notGlucose {
                        public long time;
                        public float rate;
                        public notGlucose(long t) { time = t; }
                    }
                    static class RenderCall {
                        public float value; public long glucoseTime; public boolean once;
                    }
                    public void resetStatics() {
                        onenot = this;
                        // The hub before its first reading: never null in production.
                        cannedReading = new DisplayedReading(0L, 0f, DisplayedReading.Freshness.AWAITING,
                                DisplayedReading.Reason.STARTUP);
                        glucoseRefreshHandler = new FakeHandler();
                        showalways = true; alertwatch = false; isWearable = false; Applic.unit = 0;
                        startupPendingTicket = 0L;
                        startupPendingService = null;
                        startupTicketCounter = 0L;
                        foregroundVisualGeneration = 0L;
                        broadcastSends = 0;
                        stackCalls = 0;
                        keeprunning.theservice = null;
                        keeprunning.started = false;
                        Build.VERSION.SDK_INT = 34;
                    }
                    public void setCurrentService(Service service) { keeprunning.theservice = service; }
                    public void openGate(String which) { setGate(which, true); }
                    public void closeGate(String which) { setGate(which, false); }
                    void setGate(String which, boolean open) {
                        Object lock = which.equals("resolver") ? resolverLock : renderLock;
                        synchronized (lock) {
                            if (which.equals("resolver")) resolverGateOpen = open;
                            else renderGateOpen = open;
                            lock.notifyAll();
                        }
                    }
                    void awaitGate(String which) {
                        Object lock = which.equals("resolver") ? resolverLock : renderLock;
                        synchronized (lock) {
                            long end = System.currentTimeMillis() + 8000;
                            while (!(which.equals("resolver") ? resolverGateOpen : renderGateOpen)) {
                                long w = end - System.currentTimeMillis();
                                if (w <= 0) throw new AssertionError("gate " + which + " never opened");
                                try { lock.wait(w); }
                                catch (InterruptedException e) { throw new AssertionError(e); }
                            }
                        }
                    }
                    DisplayedReading currentReading() {
                        resolverCalls++;
                        resolverEntered = true;
                        events.add("resolve");
                        awaitGate("resolver");
                        return resolverCalls > 1 && subsequentReading != null ? subsequentReading : cannedReading;
                    }
                    DisplayedReading awaitCurrentReading() { return currentReading(); }
                    Notification makeReadingNotification(DisplayedReading shown, boolean alerting) {
                        renderedReadingTime = shown.getTimeMillis();
                        renderCalls++;
                        renderEntered = true;
                        events.add("render");
                        RenderCall rc = new RenderCall();
                        rc.value = shown.getValue();
                        rc.glucoseTime = shown.getTimeMillis();
                        rc.once = !alerting;
                        lastRender = rc;
                        awaitGate("render");
                        if (renderThrow) throw new IllegalStateException("render down");
                        Notification n = new Notification();
                        n.rendered = true;
                        n.title = format(usedlocale, glucoseformat, shown.getValue());
                        return n;
                    }
                    Notification makearrownotification(int kind, float value, String message,
                            notGlucose glucose, String type, boolean once) {
                        Notification n = new Notification();
                        n.rendered = true;
                        n.title = message;
                        return n;
                    }
                    Notification makeStaleReadingNotification(DisplayedReading stale) {
                        Notification n = new Notification();
                        n.title = staleMessage(stale.getTimeMillis());
                        n.text = format(usedlocale, glucoseformat, stale.getValue());
                        n.when = stale.getTimeMillis();
                        n.showWhen = true;
                        n.ongoing = true;
                        return n;
                    }
                    Notification getforgroundnotification() {
                        startupContentCalled = true;
                        Notification n = new Notification();
                        n.startup = true;
                        n.title = "startup-content";
                        return n;
                    }
                    void startForegroundService(Service s, int id, Notification n) {
                        s.startForeground(id, n);
                    }
                    Notification.Builder mkbuilder(String type) { return new Notification.Builder(app, type); }
                    $region
                    public void fornotify(Notification notif) {
                        $fornotifyBody
                    }
                }
            """.trimIndent())
            val compiler = ToolProvider.getSystemJavaCompiler()
            check(compiler != null) { "A JDK is required for the production restore harness" }
            val classpath = System.getProperty("java.class.path")
            val output = java.io.ByteArrayOutputStream()
            check(compiler.run(null, output, output, "-classpath", classpath,
                "-d", dir.path, file.path) == 0) { "harness compilation failed:\n$output" }
            URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
                .loadClass("tk.glucodata.NotifyStartupHarness")
        }
    }
}
