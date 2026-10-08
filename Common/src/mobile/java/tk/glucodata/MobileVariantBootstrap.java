package tk.glucodata;

import android.app.Application;
import android.content.Context;

import tk.glucodata.data.ScheduledBackupWorker;

/**
 * The phone's {@link VariantBootstrap}. Everything here used to be a static on
 * {@link Specific}, which is now a one-method shim; see {@link VariantBootstrap} for why the
 * shim is still there and what pins it.
 *
 * Four of these are no-ops on the phone and have always been, which is why they are kept
 * rather than dropped: the contract is shared, and the alternative to an empty body is the
 * shared caller branching on the variant, which is what the seam exists to remove.
 */
public class MobileVariantBootstrap implements VariantBootstrap {
    @Override
    public void start(Application application) {
        // Idempotent safety net; the real registration happens in onCreate.
        Specific.registerBridges();
        watchdrip.set(Natives.getwatchdrip());
        SuperGattCallback.doGadgetbridge = Natives.getgadgetbridge();
        // Re-arm the nightly backup chain from process start, not only from the
        // settings screen: a chain that died stays dead until someone re-enqueues it.
        ScheduledBackupWorker.initialize(application);
        // The basal reminders' alarms, set again from the presets, and kept so as they change.
        tk.glucodata.journal.InsulinReminders.start(application);
        // "Log insulin" / "Log food" on a long press of the app icon.
        tk.glucodata.ui.journal.JournalQuickEntryShortcuts.publish(application);
        // Home-screen widgets: hear readings and screen-on, and catch up after a restart.
        tk.glucodata.widget.GlucoseWidgets.start(application);
        // Offered only while floating glucose and its options want it, whatever an
        // earlier version left.
        try {
            tk.glucodata.service.FloatingAccessibilityService.syncAvailability(application);
        } catch (Throwable th) {
            Log.stack("MobileVariantBootstrap", "floating accessibility", th);
        }
    }

    @Override
    public void splash(MainActivity activity) {
    }

    @Override
    public void initScreen(MainActivity activity) {
    }

    @Override
    public void wearnosensors(MainActivity activity) {
    }

    @Override
    public boolean historyDatabaseCompatible(Context context) {
        return tk.glucodata.data.HistoryDatabase.isCompatibleAtStartup(context);
    }

    @Override
    public void settext(String text) {
    }

    @Override
    public void rmlayout() {
    }

    @Override
    public boolean useCloseButton() {
        return true;
    }

}
