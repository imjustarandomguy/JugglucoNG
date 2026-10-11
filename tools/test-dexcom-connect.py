#!/usr/bin/env python3
"""Execute Dexcom's production recovery methods against deterministic Android fakes."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'Common/src/dex/java/tk/glucodata/DexGattCallback.java').read_text()
roster_source = (root / 'Common/src/main/java/tk/glucodata/SensorBluetooth.java').read_text()


def method(signature, source=source):
    start = source.index(signature)
    # Java comments and literals can contain unmatched braces.
    masked = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda match: ' ' * len(match.group()), source)
    end = masked.index('{', start) + 1
    depth = 1
    while depth:
        if masked[end] == '{': depth += 1
        elif masked[end] == '}': depth -= 1
        end += 1
    return re.sub(r'@SuppressLint\([^)]*\)|@NonNull\s*', '', source[start:end])


methods = '\n'.join(method(s) for s in [
    'public DexGattCallback(', 'private void preferBackgroundConnect(',
    'protected boolean useAutoConnect(', 'private boolean bondedHere(', 'private boolean reconnectsInBackground(',
    'protected long connectionAttemptTimeoutMillis(',
    'protected void onConnectionAttemptTimeout(', 'public synchronized boolean connectDevice(',
    'private boolean connectNow(', 'public void free(', 'private String dialledAddress(',
    'private boolean dialledByAnotherCallback(',
    'public boolean reconnect(', 'private synchronized boolean sessionUnderWay(', 'public void setDevice(',
    'public synchronized void setPause(', 'public synchronized void bonded(',
    'public synchronized void onConnectionStateChange(', 'public synchronized void close(',
    'static private PendingIntent mkintents(', 'static  PendingIntent  setalarm(',
    'private void cancelalarm(', 'private void waitShortReconnect(', 'private void runShortReconnect(',
    'protected void pendingConnectEnded(', 'private synchronized void cancelShortReconnect(',
    'private synchronized void releaseReconnectLock(',
    'public synchronized void onDescriptorWrite(', 'public synchronized void onServicesDiscovered(',
    'public synchronized void onCharacteristicChanged(' if 'public synchronized void onCharacteristicChanged(' in source
    else 'public void onCharacteristicChanged(',
    'private static PowerManager.WakeLock getwakelock(', 'private void getlock(',
    'private synchronized void releaselock(' if 'private synchronized void releaselock(' in source
    else 'private void releaselock(',
    'private void authenticate(', 'static private boolean equalpart(',
    'static boolean    createBond(', 'private void resetCerts(', 'private void resetconnect(',
    'private    void getdata(', 'private boolean isWarmupReading(', 'private void askGlucoseAgain(',
])
fields = source[source.index('private static final long DEXCOM_WARMUP_MSEC'):source.index('    public DexGattCallback(')]
phase_start = source.index('private int phase =')
phase_end = source.index(';', source.index('private static final int GetData', phase_start)) + 1
fields += source[phase_start:phase_end]
bond_start = source.index('private static final byte[][] bondBytes')
fields += source[bond_start:source.index(';', bond_start) + 1]
roster_field = re.search(r'public static final \w+<SuperGattCallback> gattcallbacks = new \w+<>\(\);', roster_source).group()
roster_methods = method('public static ArrayList<SuperGattCallback> mygatts()', roster_source)
body = r'''
package tk.glucodata;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;
import static java.util.Objects.nonNull;
class System {
    static long now=1_760_000_000_000L;
    static long currentTimeMillis(){return now;}
    static long nanoTime(){return java.lang.System.nanoTime();}
    static final java.io.PrintStream out=java.lang.System.out;
}
class SuperGattCallback {
    static boolean defaultAuto, alarmClock, doLog=false, isWearable=false;
    static final String LOG_ID="test", ALARM_SERVICE="alarm", POWER_SERVICE="power";
    static final int BOND_NONE=10, BOND_BONDED=12;
    boolean stop, removedBond, connected, bonded, justdata, lastDataInvalidDuringWarmup, backfilled, has_service;
    long dataptr, showtime, foundtime, datatime, sampletime, lastWarmupRetryAt, sensorstartmsec;
    int triedinvain, payloadCalls, unbondCalls, certsize, startpacket;
    boolean newcertificates;byte[] packet;
    static final int TRANSPORT_LE=2;
    static final int GATT_SUCCESS=0;
    BluetoothGattCharacteristic[] charact={new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic(),new BluetoothGattCharacteristic()};
    String SerialNumber, mActiveDeviceAddress, handshake;
    long[] constatchange={0,0}, wrotepass={0,0};
    BluetoothGatt mBluetoothGatt;BluetoothDevice mActiveBluetoothDevice=new BluetoothDevice();
    int directCalls, closes, rescans, disconnects, dataCommands, scans;
    static Applic app=Applic.app;
    long executorDelay=-1;
    SuperGattCallback(String serial,long ptr,int gen){SerialNumber=serial;dataptr=ptr;mActiveDeviceAddress=Natives.getDeviceAddress(ptr,true);}
    protected boolean useAutoConnect(){return defaultAuto;}
    protected long connectionAttemptTimeoutMillis(){return 0;}
    protected void onConnectionAttemptTimeout(){}
    protected boolean connectScheduled(){return false;}protected void pendingConnectEnded(){}
    // As the base class: without a device to dial, a scan looks for it.
    public synchronized boolean connectDevice(long delay){if(mActiveBluetoothDevice==null){scans++;foundtime=0;return false;}directCalls++;executorDelay=delay;mBluetoothGatt=new BluetoothGatt();return true;}
    // As the base class past its own checks: the link is dropped and dialled again.
    int reconnects;public boolean reconnect(long now){reconnects++;return connectDevice(0);}
    // As a scan delivers a device.
    public void setDevice(BluetoothDevice device){mActiveBluetoothDevice=device;}
    public void close(){closes++;mBluetoothGatt=null;}
    public void setPause(boolean pause){stop=pause;}
    public void onConnectionStateChange(BluetoothGatt g,int status,int state){}
    public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){}
    public void onServicesDiscovered(BluetoothGatt g,int status){}
    public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){}
    boolean discover(BluetoothGatt g){payloadCalls++;return true;}
    void tryer(Runnable r){r.run();}void enableIndication(BluetoothGatt g,BluetoothGattCharacteristic c){}
    void askbackfill(){}int glucoseRequests;void write(int which,byte[] value){if(which==0&&value.length==1&&value[0]==0x4E)glucoseRequests++;}void requestAuth(){}void docmd0(BluetoothGatt g){}
    void enableGattDescriptor(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){}
    void getcert(byte[] v){payloadCalls++;}void handleGlucoseResult(long res,long time){
        payloadCalls++;
        lastPublishedTime=time;
        if(publicationEntered!=null){
            if(!Thread.holdsLock(this))throw new AssertionError("native processing lost callback monitor");
            publicationEntered.countDown();
            try{if(!publicationContinue.await(5,TimeUnit.SECONDS))throw new AssertionError("publication gate timed out");}
            catch(InterruptedException e){throw new AssertionError(e);}
            SensorBluetooth.mygatts();
            if(dataptr==0)throw new AssertionError("native pointer freed during reading");
        }
    }
    long lastPublishedTime;
    static CountDownLatch publicationEntered,publicationContinue;
    synchronized void free(){setPause(true);close();dataptr=0;}
    boolean acceptConnectionAttemptCallback(BluetoothGatt g,int state){return g==mBluetoothGatt&&!stop;}
    void noteFirstGattCallback(String s,BluetoothGatt g){}
    void disconnect(){disconnects++;}
    void bonded(){}void getdatacmd(){dataCommands++;}void disablenotification(BluetoothGatt g,BluetoothGattCharacteristic c){}
    void searchforDeviceAddress(){rescans++;mActiveDeviceAddress=null;}
    void unbond(){unbondCalls++;removedBond=true;} void setConStatus(int status){}
    void askcertificate(int phase){}void sendcerts(){}void saveDeviceName(){}
    static boolean dexKnownSensor(long p){return true;} static boolean getalarmclock(){return alarmClock;}
    static PendingIntent getBroadcast(Context c,int id,Intent i,int flags){return new PendingIntent(i.action);}
}
class BluetoothProfile {static final int STATE_CONNECTED=2,STATE_DISCONNECTED=0;}
class BluetoothDevice {static final int BOND_BONDING=11;static int bondState=12;String address=Natives.stored;int getBondState(){return bondState;}String getAddress(){return address;}boolean createBond(){return true;}}
class BluetoothGatt {static final int GATT_CONNECTION_TIMEOUT=147,GATT_SUCCESS=0;BluetoothDevice getDevice(){return new BluetoothDevice();}int discoveries;boolean discoveryResult=true;boolean discoverServices(){if(!Thread.holdsLock(PowerManager.owner))throw new AssertionError("discovery outside callback monitor");discoveries++;return discoveryResult;}}
class BluetoothGattCharacteristic {static final int WRITE_TYPE_DEFAULT=2;void setWriteType(int t){}UUID getUuid(){return UUID.randomUUID();}}
class BluetoothGattDescriptor {BluetoothGattCharacteristic characteristic;BluetoothGattDescriptor(BluetoothGattCharacteristic c){characteristic=c;}BluetoothGattCharacteristic getCharacteristic(){return characteristic;}byte[] getValue(){return new byte[]{0};}UUID getUuid(){return UUID.randomUUID();}}
class Build {static class VERSION {static int SDK_INT=36;}static class VERSION_CODES {static int M=23,P=28,TIRAMISU=33;}}
class android {static class os {static class Build extends tk.glucodata.Build {}}}
class CloneSensorRegistry {static boolean clone;static boolean isCloneSensor(String s){return clone;}}
class SensorOwnershipRuntime {static boolean blocked;static boolean blocksLocalConnection(String s){return blocked;}}
class Natives {
    static String stored="F0:00:00:00:00:01";
    static long lastGlucose=-1;static boolean aesSuccess=true;static int resets;
    static long sampleAge, result=100;static boolean saveName;
    static boolean dexcomProcessData(long ptr,byte[] value,long[] timeres){timeres[0]-=sampleAge;timeres[1]=result;return saveName;}
    static long getSensorStartmsec(long ptr){return 0;}
    static void dexEndBackfill(long ptr){}
    static String getDeviceAddress(long p,boolean fresh){return fresh?null:stored;}
    static long lastglucosetime(){return lastGlucose<0?System.currentTimeMillis():lastGlucose;}
    static boolean isAuthenticated(long p){return true;}static void dexbackfill(long p,byte[] v){}
    static boolean dex8AES(long p,byte[] input,int from,byte[] output,int to){
        if(input.length<from+8)throw new AssertionError("JNI AES input would read beyond packet");
        if(!aesSuccess)return false;
        Arrays.fill(output,to,to+8,(byte)0x53);return true;
    }
    static void dexResetKeys(long p){resets++;}
    static int getDexCertSize(byte[] v){return 0;}static byte[] dexChallenger(byte[] v){return v;}
}
class Log {static void i(String a,String b){}static void d(String a,String b){}static void e(String a,String b){}static void stack(String a,String b,Throwable t){}static void showbytes(String s,byte[] v){}}
class Uri {static Uri parse(String s){return new Uri();}}
class R {static class raw {static int bonded=1;}}
class Ringtone {void setLooping(boolean b){}void play(){}}
class RingtoneManager {static Ringtone getRingtone(Context c,Uri u){return new Ringtone();}}
class Context {String getPackageName(){return "test";}static final int MODE_PRIVATE=0;Object getSystemService(String s){return s.equals("power")?Applic.power:Applic.alarms;}}
class PowerManager {
    static final int PARTIAL_WAKE_LOCK=1;
    static Object owner;static int releases;
    List<WakeLock> created=new ArrayList<>();
    WakeLock newWakeLock(int level,String tag){WakeLock w=new WakeLock();created.add(w);return w;}
    static class WakeLock {
        boolean held,referenceCounted=true;long timeout;
        void setReferenceCounted(boolean counted){referenceCounted=counted;}
        void acquire(){held=true;timeout=0;}
        void acquire(long ms){held=true;timeout=ms;}
        boolean isHeld(){return held;}
        void expire(){held=false;}
        void release(){
            if(!Thread.holdsLock(owner))throw new AssertionError("wake lock released outside callback monitor");
            if(!held)throw new AssertionError("wake lock released twice");
            held=false;releases++;
        }
    }
}
class Intent {String action;Intent(Context c,Class<?> cls){}void setAction(String s){action=s;}}
class ConnectReceiver {}
class PendingIntent {static final int FLAG_IMMUTABLE=1;String serial;PendingIntent(String s){serial=s;}}
class AlarmManager {
    boolean refused;int scheduled,cancelled;long at;PendingIntent pending;
    static class AlarmClockInfo {long at;AlarmClockInfo(long a,PendingIntent p){at=a;}}
    void setAlarmClock(AlarmClockInfo info,PendingIntent p){if(refused)throw new SecurityException();scheduled++;at=info.at;pending=p;}
    void cancel(PendingIntent p){cancelled++;if(p==pending)pending=null;}
}
class Prefs {
    Map<String,Boolean> values=new HashMap<>();
    boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}
    Prefs edit(){return this;}Prefs putBoolean(String key,boolean v){values.put(key,v);return this;}void apply(){}
}
class Applic extends Context {
    static Applic app=new Applic();static Prefs prefs=new Prefs();static AlarmManager alarms=new AlarmManager();
    static PowerManager power=new PowerManager();
    static class Scheduler {
        List<Runnable> tasks=new ArrayList<>();List<Long> delays=new ArrayList<>();
        void schedule(Runnable r,long delay,TimeUnit unit){tasks.add(r);delays.add(unit.toMillis(delay));}
        long lastDelay(){return delays.isEmpty()?-1:delays.get(delays.size()-1);}
        Runnable lastTask(){return tasks.get(tasks.size()-1);}
    }
    static Scheduler scheduler=new Scheduler();
    void redraw(){}
    static List<Runnable> ui=new ArrayList<>();static void RunOnUiThread(Runnable r){ui.add(r);}
    static void runUi(){for(Runnable r:new ArrayList<>(ui))r.run();ui.clear();}
    Prefs getSharedPreferences(String s,int mode){return prefs;}static void wakemirrors(){}
}
class SensorBluetooth {
    static SensorBluetooth blueone=new SensorBluetooth();
'''+roster_field+roster_methods+r'''
    void connectToActiveDevice(SuperGattCallback cb,long delay){cb.connectDevice(delay);}
}
public class DexGattCallback extends SuperGattCallback {
    PendingIntent onalarm;static int alarmrequest=14;
    private PowerManager.WakeLock wakelock;
    private final byte[] random8=new byte[8];
'''+fields+methods+r'''
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static boolean waitsUnderLock(DexGattCallback cb,long delay){
        PowerManager.WakeLock lock=cb.reconnectlock;
        return Applic.scheduler.lastDelay()==delay&&cb.shortReconnectPending&&lock!=null&&lock.held
            &&lock.timeout==delay+2000&&!lock.referenceCounted&&Applic.alarms.pending==null;
    }
    static boolean shortWaitEnded(DexGattCallback cb){
        return !cb.shortReconnectPending&&(cb.reconnectlock==null||!cb.reconnectlock.held);
    }
    static void previousReadingAskedAgain() {
        for(boolean wear:new boolean[]{false,true}) {
            // The reading already published comes back: not published again, asked for twice more.
            DexGattCallback cb=fresh();isWearable=wear;BluetoothGatt gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.publishedSampleMs=System.now-300000;
            Natives.sampleAge=300000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==0&&Applic.scheduler.lastDelay()==1000&&cb.datatime==System.now,"reading already published counted again or not asked again");
            System.now+=1000;Applic.scheduler.lastTask().run();check(cb.glucoseRequests==1,"glucose not asked again");
            cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==0&&Applic.scheduler.lastDelay()==1000,"second ask missing");
            System.now+=1000;Applic.scheduler.lastTask().run();check(cb.glucoseRequests==2,"glucose not asked a second time");
            cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==0&&Applic.scheduler.lastDelay()==10,"asked more than twice, or published the reading it had");
            cb.onConnectionStateChange(gatt,19,0);System.now+=300000;cb.connectDevice(0);gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(Applic.scheduler.lastDelay()==1000,"a new session did not ask again");

            // The new value is ready a second later: published with its own time.
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.publishedSampleMs=System.now-300000;
            Natives.sampleAge=300000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            System.now+=1000;Applic.scheduler.lastTask().run();
            Natives.sampleAge=3000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==1&&cb.lastPublishedTime==System.now-3000&&cb.publishedSampleMs==System.now-3000
                &&Applic.scheduler.lastDelay()==10,"fresh reading after asking again not published");

            // An old reading this device lacks is published at once, and the new one still asked for.
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.publishedSampleMs=System.now-600000;
            Natives.sampleAge=300000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==1&&Applic.scheduler.lastDelay()==1000,"missed reading dropped, or the new one not asked for");

            // Gone before the second ask: nothing is written on a closed link.
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.publishedSampleMs=System.now-300000;
            Natives.sampleAge=300000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            Runnable ask=Applic.scheduler.lastTask();cb.onConnectionStateChange(gatt,19,0);ask.run();
            check(cb.glucoseRequests==0,"asked again on a closed link");

            // A fresh reading or a failed one is never asked for again.
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;cb.onConnectionStateChange(gatt,0,2);
            Natives.sampleAge=4000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.payloadCalls==1&&Applic.scheduler.lastDelay()==10,"fresh reading asked again");
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;cb.onConnectionStateChange(gatt,0,2);
            Natives.result=0;Natives.sampleAge=300000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(Applic.scheduler.lastDelay()==10,"failed reading asked again");
        }
    }
    static void shortReconnectWaits() {
        DexGattCallback cb=fresh();cb.close();int calls=cb.directCalls;
        check(cb.connectDevice(5000)&&waitsUnderLock(cb,5000)&&Applic.alarms.scheduled==0&&cb.directCalls==calls,"short reconnect used an alarm clock or connected at once");
        Applic.scheduler.lastTask().run();
        check(cb.directCalls==calls+1&&shortWaitEnded(cb),"short wait did not connect or kept its wake lock");
        cb=fresh();cb.close();calls=cb.directCalls;cb.connectDevice(5000);Runnable replaced=Applic.scheduler.lastTask();
        cb.connectDevice(6000);check(waitsUnderLock(cb,6000),"later short wait did not replace the first");
        replaced.run();check(cb.directCalls==calls&&cb.reconnectlock.held,"replaced short wait connected or released its successor's lock");
        cb=fresh();cb.close();calls=cb.directCalls;cb.connectDevice(5000);Runnable cancelled=Applic.scheduler.lastTask();
        cb.connectDevice(0);check(cb.directCalls==calls+1&&shortWaitEnded(cb),"immediate connect did not cancel the short wait");
        cancelled.run();check(cb.directCalls==calls+1,"cancelled short wait connected");
        cb=fresh();cb.close();cb.connectDevice(5000);cb.connectDevice(300000);
        check(Applic.alarms.scheduled==1&&shortWaitEnded(cb),"long delay kept the short wait");
        cb=fresh();cb.close();cb.connectDevice(300000);cb.connectDevice(5000);
        check(waitsUnderLock(cb,5000),"short wait kept the alarm clock");
        cb=fresh();cb.close();calls=cb.directCalls;cb.connectDevice(5000);cancelled=Applic.scheduler.lastTask();cb.setPause(true);
        check(shortWaitEnded(cb),"pause kept the short wait or its wake lock");
        cancelled.run();check(cb.directCalls==calls,"paused callback connected after its short wait");
        cb=fresh();cb.close();cb.connectDevice(7001);
        check(Applic.alarms.scheduled==1&&shortWaitEnded(cb),"a wait past the cap skipped the alarm clock");
    }
    static void unbondedG7ConnectsLikeAFirstPairing() {
        for(boolean wear:new boolean[]{false,true}) {
            // Bonded here: the background preference and the stored address stay in use.
            DexGattCallback cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");
            cb.close();int calls=cb.directCalls;
            check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"bonded G7 lost its background connect");
            cb.connectDevice(0);
            check(cb.directCalls==calls+1&&cb.scans==0&&cb.mActiveBluetoothDevice!=null,"bonded G7 scanned instead of dialling");

            // Its bond removed: never the background connect, which cannot complete without a bond.
            cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");cb.close();calls=cb.directCalls;
            BluetoothDevice.bondState=BOND_NONE;
            check(!cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==45000,"unbonded G7 kept the background connect");
            check(!cb.reconnectsInBackground(),"unbonded G7 skipped its next-slot alarm");
            // Only its stored address known: a scan finds it first, as on a first pairing.
            cb.connectDevice(0);
            check(cb.directCalls==calls&&cb.scans==1&&cb.mActiveBluetoothDevice==null
                &&Natives.stored.equals(cb.mActiveDeviceAddress),"unbonded G7 dialled its stored address instead of scanning");
            // The scan found it: dialled directly, and pairing goes on from the connection.
            cb.foundtime=System.now;cb.setDevice(new BluetoothDevice());
            cb.connectDevice(0);
            check(cb.directCalls==calls+1&&cb.scans==1&&!cb.useAutoConnect(),"G7 the scan found not dialled directly");
            // A dial between its adverts fails: still direct while it has no bond.
            cb.onConnectionStateChange(cb.mBluetoothGatt,147,0);
            check(!cb.useAutoConnect()&&cb.directCalls==calls+2&&cb.scans==1,"failed dial of an unbonded G7 went to the background");
            // Bonded again: back to the background connect it preferred.
            BluetoothDevice.bondState=BOND_BONDED;
            check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"bonded G7 lost its preference");

            // The bond this callback removed, before the stack reports it gone.
            cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");cb.removedBond=true;cb.close();calls=cb.directCalls;
            check(!cb.useAutoConnect()&&!cb.reconnectsInBackground(),"G7 whose bond was removed kept the background connect");
            cb.connectDevice(0);
            check(cb.scans==1&&cb.directCalls==calls,"G7 whose bond was removed dialled its stored address");

            // The application-wide setting still applies, to a device the scan found.
            cb=fresh();isWearable=wear;defaultAuto=true;BluetoothDevice.bondState=BOND_NONE;cb.close();
            check(cb.useAutoConnect(),"application background setting ignored");
            cb.connectDevice(0);
            check(cb.scans==1,"unbonded G7 dialled its stored address under the application setting");
        }
    }
    static void unbondedG7DialledOnlyWithTheScannedDevice() {
        for(boolean wear:new boolean[]{false,true}) {
            // A scan found it once; since then its bond went, and a roster update rebuilt
            // the device from the stored address: scanned for again, not dialled.
            DexGattCallback cb=fresh();isWearable=wear;cb.foundtime=System.now;cb.setDevice(new BluetoothDevice());cb.close();
            BluetoothDevice.bondState=BOND_NONE;int calls=cb.directCalls;
            cb.mActiveBluetoothDevice=new BluetoothDevice();
            cb.connectDevice(0);
            check(cb.scans==1&&cb.directCalls==calls&&cb.mActiveBluetoothDevice==null,"unbonded G7 dialled from its stored address");
            // The device the next scan delivers is dialled.
            BluetoothDevice scanned=new BluetoothDevice();cb.foundtime=System.now;cb.setDevice(scanned);
            cb.connectDevice(0);
            check(cb.directCalls==calls+1&&cb.mActiveBluetoothDevice==scanned,"device the scan found not dialled");
            // Bonded, the device made from the stored address is dialled as before.
            cb=fresh();isWearable=wear;cb.foundtime=System.now;cb.setDevice(new BluetoothDevice());cb.close();calls=cb.directCalls;
            cb.mActiveBluetoothDevice=new BluetoothDevice();cb.connectDevice(0);
            check(cb.directCalls==calls+1&&cb.scans==0,"bonded G7 not dialled from its stored address");
        }
    }
    static void bondBackAfterItsRemoval() {
        for(boolean wear:new boolean[]{false,true}) {
            // Removed by this callback (tried too often), then paired again: the bond broadcast.
            DexGattCallback cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");cb.removedBond=true;
            check(!cb.useAutoConnect()&&!cb.reconnectsInBackground(),"removed bond still used");
            cb.bonded();
            check(!cb.removedBond&&cb.useAutoConnect()&&cb.reconnectsInBackground()&&cb.connectionAttemptTimeoutMillis()==0,
                "G7 paired again kept dialling directly");
            // Or found bonded when it connects.
            cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");cb.removedBond=true;
            cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);
            check(!cb.removedBond&&cb.useAutoConnect(),"G7 bonded at connect kept dialling directly");
            // Still unbonded: the removal stands.
            cb=fresh();isWearable=wear;cb.preferBackgroundConnect("test");cb.removedBond=true;BluetoothDevice.bondState=BOND_NONE;
            cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);cb.bonded();
            check(cb.removedBond&&!cb.useAutoConnect(),"removal forgotten while the G7 is unbonded");
        }
    }
    static void lossAlarmSparesASessionUnderWay() {
        for(boolean wear:new boolean[]{false,true}) {
            // A background connect issued minutes ago, answered at the G7's slot: the loss
            // alarm then leaves the link alone.
            DexGattCallback cb=fresh();isWearable=wear;BluetoothGatt gatt=cb.mBluetoothGatt;int calls=cb.directCalls;
            System.now+=300000;cb.onConnectionStateChange(gatt,0,2);
            System.now+=20000;
            check(cb.reconnect(System.now)&&cb.reconnects==0&&cb.mBluetoothGatt==gatt&&cb.directCalls==calls,
                "loss alarm closed a session under way");
            // Another sensor's reading passes a time one showtime back.
            check(cb.reconnect(System.now-cb.showtime+20)&&cb.reconnects==0,"another sensor's reading closed a session under way");
            // A link up for a minute is not a session any more.
            System.now+=40000;cb.reconnect(System.now);
            check(cb.reconnects==1&&cb.directCalls==calls+1,"link up for a minute not reconnected");
            // Once the session ended, and for a connect still pending, as before.
            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.onConnectionStateChange(gatt,19,0);cb.reconnect(System.now);
            check(cb.reconnects==1,"ended session kept the loss alarm from reconnecting");
            cb=fresh();isWearable=wear;System.now+=300000;cb.reconnect(System.now);
            check(cb.reconnects==1,"pending connect not reconnected");
        }
    }
    static void duplicateCallbackKeepsTheBond() {
        // Two callbacks dialling one G7, as a roster rebuild can find: the one it
        // drops leaves the bond to the one that keeps reading the sensor.
        DexGattCallback kept=fresh(),dropped=fresh();
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(kept);SensorBluetooth.gattcallbacks.add(dropped);
        dropped.free();
        check(dropped.unbondCalls==0&&dropped.stop&&dropped.dataptr==0,"dropping a second callback removed the bond of the G7 still read");
        // The last callback of the G7 going is the sensor going.
        SensorBluetooth.gattcallbacks.remove(dropped);kept.free();
        check(kept.unbondCalls==1,"the last callback of a G7 kept its bond");
        // Neither another G7's callback nor a stopped one keeps it.
        DexGattCallback other=fresh(),gone=fresh();other.mActiveBluetoothDevice.address="F0:00:00:00:00:02";
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(other);SensorBluetooth.gattcallbacks.add(gone);
        gone.free();check(gone.unbondCalls==1,"another G7's callback kept this one's bond");
        DexGattCallback paused=fresh(),freed=fresh();paused.setPause(true);
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(paused);SensorBluetooth.gattcallbacks.add(freed);
        freed.free();check(freed.unbondCalls==1,"a stopped callback kept the bond");
        // Without a device, its stored address names the G7.
        DexGattCallback reading=fresh(),scanning=fresh();scanning.mActiveBluetoothDevice=null;
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(reading);SensorBluetooth.gattcallbacks.add(scanning);
        scanning.free();check(scanning.unbondCalls==0,"a callback between scans removed the bond of the G7 still read");
        SensorBluetooth.gattcallbacks.clear();
    }
    static DexGattCallback fresh(){
        Applic.prefs=new Prefs();Applic.alarms=new AlarmManager();
        Applic.power=new PowerManager();PowerManager.releases=0;Applic.ui.clear();Applic.scheduler=new Applic.Scheduler();BluetoothDevice.bondState=BOND_BONDED;
        defaultAuto=false;alarmClock=false;isWearable=false;CloneSensorRegistry.clone=false;SensorOwnershipRuntime.blocked=false;
        Natives.lastGlucose=-1;Natives.aesSuccess=true;Natives.resets=0;
        Natives.sampleAge=0;Natives.result=100;Natives.saveName=false;System.now=1_760_000_000_000L;
        DexGattCallback cb=new DexGattCallback("sensor-A",1);PowerManager.owner=cb;cb.connectDevice(0);return cb;
    }
    static void publicationAndTeardownFinish(boolean remove) throws Exception {
        final DexGattCallback cb=fresh();
        final BluetoothGatt current=cb.mBluetoothGatt;
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(cb);
        publicationEntered=new CountDownLatch(1);publicationContinue=new CountDownLatch(1);
        final CountDownLatch teardownStarted=new CountDownLatch(1),finished=new CountDownLatch(2);
        final List<Throwable> errors=Collections.synchronizedList(new ArrayList<>());
        Thread reading=new Thread(()->{
            try{cb.onCharacteristicChanged(current,cb.charact[0],new byte[]{0x4e});}
            catch(Throwable e){errors.add(e);}finally{finished.countDown();}
        });
        Thread teardown=new Thread(()->{
            teardownStarted.countDown();
            try{synchronized(SensorBluetooth.gattcallbacks){
                if(remove){cb.free();SensorBluetooth.gattcallbacks.remove(cb);}else cb.setPause(true);
            }}
            catch(Throwable e){errors.add(e);}finally{finished.countDown();}
        });
        reading.setDaemon(true);teardown.setDaemon(true);
        reading.start();check(publicationEntered.await(2,TimeUnit.SECONDS),"reading did not reach publication");
        teardown.start();check(teardownStarted.await(2,TimeUnit.SECONDS),"teardown did not start");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(teardown.getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(1);
        check(teardown.getState()==Thread.State.BLOCKED,"teardown did not contend with reading");
        CountDownLatch snapshotRead=new CountDownLatch(1);
        Thread reader=new Thread(()->{
            try{check(SensorBluetooth.mygatts().contains(cb),"snapshot lost current callback");}
            catch(Throwable e){errors.add(e);}finally{snapshotRead.countDown();}
        });
        reader.setDaemon(true);reader.start();
        boolean readable=snapshotRead.await(2,TimeUnit.SECONDS);
        publicationContinue.countDown();
        check(readable,"UI roster snapshot blocked behind reading/teardown");
        check(finished.await(2,TimeUnit.SECONDS),"reading/teardown lock inversion deadlocked");
        check(errors.isEmpty(),"concurrent reading failure: "+errors);
        check(cb.payloadCalls==1&&cb.stop,"reading or teardown lost");
        check(SensorBluetooth.mygatts().contains(cb)!=remove,"teardown lost roster membership update");
        cb.onCharacteristicChanged(current,cb.charact[0],new byte[]{0x4e});
        check(cb.payloadCalls==1,"paused/removed callback published again");
        publicationEntered=null;publicationContinue=null;
        SensorBluetooth.gattcallbacks.clear();
    }
    static void stableRosterSnapshots() {
        DexGattCallback first=fresh(),second=fresh();
        SensorBluetooth.gattcallbacks.clear();SensorBluetooth.gattcallbacks.add(first);
        ArrayList<SuperGattCallback> snapshot=SensorBluetooth.mygatts();
        Iterator<SuperGattCallback> iterator=SensorBluetooth.gattcallbacks.iterator();
        SensorBluetooth.gattcallbacks.remove(first);SensorBluetooth.gattcallbacks.add(second);
        check(snapshot.size()==1&&snapshot.get(0)==first,"roster snapshot changed after mutation");
        check(iterator.next()==first&&!iterator.hasNext(),"roster iteration changed after mutation");
        snapshot.clear();check(SensorBluetooth.mygatts().get(0)==second,"snapshot mutated live roster");
        SensorBluetooth.gattcallbacks.clear();
    }
    static void emptySession(DexGattCallback cb) {
        if(cb.mBluetoothGatt==null) {System.now+=5000;cb.connectDevice(0);}
        BluetoothGatt gatt=cb.mBluetoothGatt;
        cb.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_CONNECTED);
        cb.phase=GetData;
        cb.onConnectionStateChange(gatt,19,BluetoothProfile.STATE_DISCONNECTED);
    }
    static void preservesBondBeforeFirstReading() {
        for(boolean known:new boolean[]{false,true}) {
            DexGattCallback cb=fresh();cb.known=known;Natives.lastGlucose=0;
            emptySession(cb);
            check(cb.unbondCalls==0,"first live-link disconnect before BG erased bond (known="+known+")");
            check(cb.triedinvain==1&&cb.mActiveDeviceAddress.equals(Natives.stored),"empty session lost retry count or address");
        }
        for(boolean wear:new boolean[]{false,true}) {
            DexGattCallback cb=fresh();cb.known=false;isWearable=wear;
            int sessions=wear?3:6;
            for(int i=1;i<=sessions;i++) {
                emptySession(cb);
                check(cb.unbondCalls==(i==sessions?1:0),"empty-session bond recovery fired too early or never fired");
            }
            check(cb.removedBond&&cb.triedinvain==-10,"bond recovery did not reset retry state");
        }
        for(boolean wear:new boolean[]{false,true}) {
            DexGattCallback cb=fresh();cb.known=true;isWearable=wear;
            int sessions=wear?3:6;
            for(int i=1;i<=sessions;i++) {
                if(cb.mBluetoothGatt==null) {System.now+=5000;cb.connectDevice(0);}
                BluetoothGatt gatt=cb.mBluetoothGatt;
                cb.onConnectionStateChange(gatt,0,2);cb.phase=ChallengeReply;
                cb.onCharacteristicChanged(gatt,cb.charact[1],new byte[]{0x05,0,3});
                check(Natives.resets==i&&cb.disconnects==i,"bond rejection did not reset keys/disconnect");
                cb.onConnectionStateChange(gatt,19,0);
                check(cb.unbondCalls==(i==sessions?1:0),"repeated pre-data bond failures did not recover within bounds");
            }
        }
        DexGattCallback cb=fresh();cb.datatime=System.currentTimeMillis();cb.triedinvain=100;Natives.lastGlucose=0;
        emptySession(cb);check(cb.unbondCalls==0&&cb.triedinvain==100,"sensor with readings lost its bond");
        cb=fresh();cb.known=false;cb.triedinvain=5;
        cb.onConnectionStateChange(cb.mBluetoothGatt,147,0);
        check(cb.unbondCalls==0&&cb.triedinvain==5&&cb.useAutoConnect(),"failed dial counted as an empty live session");
        cb=fresh();BluetoothGatt old=cb.mBluetoothGatt;cb.onConnectionStateChange(old,0,2);
        cb.close();cb.connectDevice(0);cb.phase=GetData;
        cb.onConnectionStateChange(old,19,0);
        check(cb.unbondCalls==0&&cb.triedinvain==0,"retired disconnect altered bond recovery");
        cb=fresh();cb.triedinvain=100;Natives.lastGlucose=0;old=cb.mBluetoothGatt;
        cb.onConnectionStateChange(old,0,2);cb.close();cb.connectDevice(0);
        cb.onConnectionStateChange(cb.mBluetoothGatt,147,0);
        check(cb.unbondCalls==0&&cb.triedinvain==100,"missing old disconnect let a failed dial erase the bond");
    }
    static void recognizesVerifiedSensor() {
        byte[] response=new byte[17];Arrays.fill(response,1,9,(byte)0x53);
        DexGattCallback cb=fresh();cb.known=false;cb.phase=RequestAuth;
        cb.onCharacteristicChanged(cb.mBluetoothGatt,cb.charact[1],response);
        check(cb.known&&cb.phase==ChallengeReply,"verified sensor stayed unknown until first BG");
        cb=fresh();cb.known=false;cb.phase=RequestAuth;response[1]^=1;
        cb.onCharacteristicChanged(cb.mBluetoothGatt,cb.charact[1],response);
        check(!cb.known&&Natives.resets==1&&cb.disconnects==1,"mismatched challenge established identity");
        cb=fresh();cb.known=false;cb.phase=RequestAuth;Natives.aesSuccess=false;
        cb.onCharacteristicChanged(cb.mBluetoothGatt,cb.charact[1],new byte[17]);
        check(!cb.known&&Natives.resets==1&&cb.disconnects==1,"failed native AES established identity");
        Arrays.fill(response,1,9,(byte)0x53);
        for(int length:new int[]{0,8,9,16}) {
            cb=fresh();cb.known=false;cb.phase=RequestAuth;
            cb.onCharacteristicChanged(cb.mBluetoothGatt,cb.charact[1],Arrays.copyOf(response,length));
            check(!cb.known&&Natives.resets==1&&cb.disconnects==1,"truncated challenge established identity");
        }
    }
    static void quietReconnectsRespectReceptionAndSampleTime() {
        for(boolean wear:new boolean[]{false,true}) {
            DexGattCallback cb=fresh();isWearable=wear;
            BluetoothGatt gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);
            long received=System.now;Natives.sampleAge=120000;
            cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            check(cb.datatime==received&&cb.sampletime==received-120000,"response/sample clocks were mixed");
            check(cb.lastPublishedTime==received-120000,"published glucose lost its actual timestamp");
            cb.onConnectionStateChange(gatt,19,0);
            check(waitsUnderLock(cb,7000)&&Applic.alarms.scheduled==0&&cb.directCalls==1,"old sample caused immediate post-reading reconnect, or an alarm clock for its quiet period");
            check(cb.useAutoConnect()&&cb.wakelock==null,"post-reading policy/wake lock regressed");
            cb.connectDevice(0);gatt=cb.mBluetoothGatt;
            check(shortWaitEnded(cb),"immediate connect left the quiet wait or its wake lock");
            cb.onConnectionStateChange(gatt,0,2);
            check(!cb.connected&&cb.wakelock==null,"old sample bypassed recent-response guard");
            cb.onConnectionStateChange(gatt,19,0);
            check(waitsUnderLock(cb,5000)&&Applic.alarms.scheduled==0&&cb.directCalls==2,"recent-response link loss redialled immediately");

            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);Natives.sampleAge=120000;
            cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            System.now+=10000;cb.onConnectionStateChange(gatt,19,0);
            check(waitsUnderLock(cb,5000)&&Applic.alarms.scheduled==0&&cb.directCalls==1,"elapsed quiet period bypassed reconnect floor");

            cb=fresh();isWearable=wear;alarmClock=true;gatt=cb.mBluetoothGatt;BluetoothDevice.bondState=BOND_NONE;
            Natives.sampleAge=120000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            cb.onConnectionStateChange(gatt,19,0);
            check(Applic.alarms.at==System.now+152500&&shortWaitEnded(cb),"next-slot alarm followed reception instead of sample age");

            for(int kind=0;kind<3;kind++) {
                // Bonded, known, background preference: no next-slot alarm clock, the quiet wait instead.
                cb=fresh();isWearable=wear;alarmClock=true;gatt=cb.mBluetoothGatt;
                if(kind==1)cb.known=false;
                if(kind==2)cb.removedBond=true;
                Natives.sampleAge=120000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
                cb.onConnectionStateChange(gatt,19,0);
                if(kind==0)check(waitsUnderLock(cb,7000)&&Applic.alarms.scheduled==0&&cb.useAutoConnect(),"bonded background G7 kept the next-slot alarm clock");
                else check(Applic.alarms.at==System.now+152500&&shortWaitEnded(cb),"unknown or unbonded G7 lost its next-slot alarm");
            }

            cb=fresh();isWearable=wear;alarmClock=true;gatt=cb.mBluetoothGatt;BluetoothDevice.bondState=BOND_NONE;
            Natives.sampleAge=280000;cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            cb.onConnectionStateChange(gatt,19,0);
            check(waitsUnderLock(cb,5000)&&Applic.alarms.scheduled==0,"past slot alarm bypassed reconnect floor");

            cb=fresh();isWearable=wear;alarmClock=true;gatt=cb.mBluetoothGatt;BluetoothDevice.bondState=BOND_NONE;
            cb.datatime=System.now;cb.justdata=true;cb.sampletime=0;
            cb.onConnectionStateChange(gatt,19,0);
            check(Applic.alarms.at==System.now+272500,"missing sample time lost reception fallback");

            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,0,2);cb.onConnectionStateChange(gatt,19,0);
            check(waitsUnderLock(cb,5000)&&Applic.alarms.scheduled==0&&cb.directCalls==1&&!cb.useAutoConnect(),"empty live session redialled immediately or changed direct policy");
            cb.setPause(true);
            check(Applic.alarms.pending==null&&shortWaitEnded(cb),"pause retained empty-session reconnect wait");

            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            cb.onConnectionStateChange(gatt,133,0);
            check(cb.directCalls==2&&Applic.alarms.scheduled==0&&shortWaitEnded(cb)&&cb.useAutoConnect(),"failed dial acquired a quiet delay");

            cb=fresh();isWearable=wear;gatt=cb.mBluetoothGatt;
            Natives.result=0;cb.sensorstartmsec=System.now-600000;
            cb.onCharacteristicChanged(gatt,cb.charact[0],new byte[]{0x4e});
            cb.onConnectionStateChange(gatt,19,0);
            check(Applic.alarms.at==cb.sensorstartmsec+1800000+15000,"warmup timing was replaced with quiet delay");
        }
    }
    public static void main(String[] args) throws Exception {
        DexGattCallback cb=fresh();BluetoothGatt first=cb.mBluetoothGatt;
        check(cb.mActiveDeviceAddress.equals(Natives.stored),"process startup lost saved address");
        check(!cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==45000,"initial direct attempt policy");
        cb.onConnectionStateChange(first,147,0);
        check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"timeout did not switch to background");
        check(cb.rescans==0&&cb.mActiveDeviceAddress.equals(Natives.stored),"timeout discarded identity");
        check(new DexGattCallback("sensor-A",1).useAutoConnect(),"process restart forgot mode");
        check(!new DexGattCallback("sensor-B",2).useAutoConnect(),"mode leaked across sensors");
        BluetoothGatt current=cb.mBluetoothGatt;int calls=cb.directCalls, releases=PowerManager.releases;
        cb.onConnectionStateChange(first,147,0);cb.onConnectionStateChange(first,0,2);
        check(cb.mBluetoothGatt==current&&cb.directCalls==calls&&PowerManager.releases==releases,"retired callback changed live attempt");
        cb.onServicesDiscovered(first,0);cb.onDescriptorWrite(first,new BluetoothGattDescriptor(cb.charact[3]),0);cb.onCharacteristicChanged(first,cb.charact[0],new byte[0]);
        check(cb.payloadCalls==0&&!cb.has_service,"retired payload callback mutated new session");
        cb.onServicesDiscovered(current,0);cb.onCharacteristicChanged(current,cb.charact[0],new byte[]{0x4e});check(cb.payloadCalls==2,"current payload callback rejected");
        cb=fresh();cb.onConnectionAttemptTimeout();check(cb.useAutoConnect(),"silent direct attempt did not fall back");
        cb=fresh();cb.onConnectionStateChange(cb.mBluetoothGatt,133,0);check(cb.useAutoConnect(),"legacy failure did not fall back");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);cb.onConnectionStateChange(first,147,0);
        check(!cb.useAutoConnect(),"connected link loss treated as failed direct dial");
        cb=fresh();first=cb.mBluetoothGatt;cb.datatime=System.currentTimeMillis();cb.onConnectionStateChange(first,0,2);cb.onConnectionStateChange(first,147,0);
        check(!cb.useAutoConnect(),"recent-reading early return forgot connected attempt");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);cb.close();cb.connectDevice(0);cb.onConnectionStateChange(cb.mBluetoothGatt,147,0);
        check(cb.useAutoConnect(),"missing old disconnect prevented new-attempt fallback");
        cb=fresh();first=cb.mBluetoothGatt;cb.datatime=System.currentTimeMillis();cb.justdata=true;cb.onConnectionStateChange(first,19,0);
        check(cb.useAutoConnect()&&waitsUnderLock(cb,7000)&&Applic.alarms.scheduled==0&&cb.directCalls==1,"data reconnect did not wait its quiet period under a wake lock");
        cb=fresh();first=cb.mBluetoothGatt;cb.justdata=true;cb.lastDataInvalidDuringWarmup=true;cb.lastWarmupRetryAt=System.currentTimeMillis()+600000;cb.onConnectionStateChange(first,19,0);
        check(Applic.alarms.scheduled==1&&cb.directCalls==1,"warmup used executor timer");
        cb=fresh();cb.close();calls=cb.directCalls;cb.connectDevice(300000);
        check(Applic.alarms.scheduled==1&&cb.directCalls==calls,"delayed connect set executor latch");
        cb.connectDevice(0);check(cb.directCalls==calls+1&&Applic.alarms.pending==null,"alarm blocked immediate recovery");
        cb=fresh();Applic.alarms.refused=true;cb.connectDevice(300000);check(cb.executorDelay==0&&cb.directCalls==2,"alarm denial left no retry");
        cb=fresh();cb.stop=true;check(!cb.connectDevice(300000)&&Applic.alarms.scheduled==0,"paused sensor scheduled alarm");
        cb=fresh();cb.connectDevice(300000);cb.setPause(true);check(Applic.alarms.pending==null&&!cb.connectDevice(0),"pause retained wakeup alarm");
        cb=fresh();cb.dataptr=0;check(!cb.connectDevice(300000),"removed sensor scheduled alarm");
        cb=fresh();CloneSensorRegistry.clone=true;check(!cb.connectDevice(300000),"clone sensor scheduled alarm");
        cb=fresh();SensorOwnershipRuntime.blocked=true;check(!cb.connectDevice(300000),"released sensor scheduled alarm");
        cb=fresh();defaultAuto=true;check(cb.useAutoConnect()&&cb.connectionAttemptTimeoutMillis()==0,"application background setting ignored");
        cb=fresh();first=cb.mBluetoothGatt;cb.onConnectionStateChange(first,0,2);
        PowerManager.WakeLock firstLock=cb.wakelock;check(firstLock.held,"connection did not acquire wake lock");
        check(firstLock.timeout>0&&!firstLock.referenceCounted,"connection wake lock was untimed or reference counted");
        cb.onConnectionStateChange(first,0,2);
        check(cb.wakelock==firstLock&&Applic.power.created.size()==1,"repeated connection stacked another wake lock");
        cb.close();check(!firstLock.held&&cb.wakelock==null&&PowerManager.releases==1,"Bluetooth-off close leaked wake lock");
        cb.close();check(PowerManager.releases==1,"repeated close released wake lock twice");
        cb.connectDevice(0);current=cb.mBluetoothGatt;cb.onConnectionStateChange(current,0,2);
        PowerManager.WakeLock nextLock=cb.wakelock;
        cb.onConnectionStateChange(first,0,0);check(nextLock.held&&cb.wakelock==nextLock,"retired disconnect released replacement wake lock");
        cb.onConnectionStateChange(current,19,0);check(!nextLock.held&&PowerManager.releases==2,"normal disconnect leaked/double-released wake lock");
        cb=fresh();cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);firstLock=cb.wakelock;cb.setPause(true);
        check(!firstLock.held&&cb.wakelock==null,"pause leaked wake lock");
        cb.close();check(PowerManager.releases==1,"close after pause released twice");
        cb=fresh();cb.onConnectionStateChange(cb.mBluetoothGatt,0,2);cb.wakelock.expire();
        cb.close();check(cb.wakelock==null&&PowerManager.releases==0,"wake lock released again after its timeout");
        cb=fresh();current=cb.mBluetoothGatt;cb.bonded();check(cb.dataCommands==1&&current.discoveries==0,"bonded discovery not queued");
        Applic.runUi();check(current.discoveries==1,"current bonded discovery skipped");
        cb=fresh();first=cb.mBluetoothGatt;cb.bonded();cb.close();Applic.runUi();
        check(first.discoveries==0&&cb.disconnects==0,"queued discovery used closed GATT");
        cb=fresh();first=cb.mBluetoothGatt;cb.bonded();cb.close();cb.connectDevice(0);current=cb.mBluetoothGatt;Applic.runUi();
        check(first.discoveries==0&&current.discoveries==0&&cb.disconnects==0,"queued discovery used replacement GATT");
        cb=fresh();current=cb.mBluetoothGatt;cb.bonded();cb.setPause(true);Applic.runUi();check(current.discoveries==0,"queued discovery ignored pause");
        cb=fresh();cb.close();cb.bonded();Applic.runUi();check(cb.dataCommands==0,"bonded without GATT tried command");
        cb=fresh();cb.mActiveBluetoothDevice=null;cb.bonded();check(Applic.ui.isEmpty(),"bonded without device queued discovery");
        cb=fresh();current=cb.mBluetoothGatt;current.discoveryResult=false;cb.bonded();Applic.runUi();check(cb.disconnects==1,"current discovery failure lost recovery");
        publicationAndTeardownFinish(false);publicationAndTeardownFinish(true);stableRosterSnapshots();
        preservesBondBeforeFirstReading();recognizesVerifiedSensor();quietReconnectsRespectReceptionAndSampleTime();shortReconnectWaits();previousReadingAskedAgain();
        unbondedG7ConnectsLikeAFirstPairing();duplicateCallbackKeepsTheBond();
        unbondedG7DialledOnlyWithTheScannedDevice();bondBackAfterItsRemoval();lossAlarmSparesASessionUnderWay();
        System.out.println("PASS: Dexcom recovery, quiet/slot timing, short waits without alarm clocks, background reconnect without a slot alarm, the reading before asked again, bond/challenge guards, an unbonded G7 scanned for and dialled directly, a G7's bond kept while another callback reads it, an unbonded G7 dialled only with the device a scan found, a bond back after its removal, a session under way spared by the loss alarm, callback lifecycle, concurrent publication/teardown and nonblocking roster snapshots");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='dexcom-connect-') as directory:
    directory = Path(directory)
    java = directory / 'DexGattCallback.java'
    java.write_text(body)
    subprocess.run(['javac', '-d', str(directory), str(java)], check=True)
    subprocess.run(['java', '-cp', str(directory), 'tk.glucodata.DexGattCallback'], check=True)
