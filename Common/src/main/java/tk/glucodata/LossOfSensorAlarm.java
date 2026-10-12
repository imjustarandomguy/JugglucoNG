/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.PendingIntent.getBroadcast;
import static android.content.Context.ALARM_SERVICE;
import static tk.glucodata.Log.doLog;

public class LossOfSensorAlarm extends BroadcastReceiver {
static final private String LOG_ID="LossOfSensorAlarm";
@Override
public void onReceive(Context context, Intent intent) {
       {if(doLog) {Log.i(LOG_ID,"onReceive ");};};
       Applic app=(Applic) context.getApplicationContext();
       app.initproc();
       SuperGattCallback.initAlarmTalk();
       SuperGattCallback.glucosealarms.handlealarm();
       if(!keeprunning.started) {
            Applic.possiblybluetooth(context) ;
            }
        }

static private PendingIntent onalarm=null ;

/** When the alarm is due (wall clock), 0 while none is set. */
static private volatile long scheduledAtMillis = 0L;

static void mkintents(Context context) {
    if(onalarm==null) {
        final int alarmrequest = 4;
        final int alarmflags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmflags = PendingIntent.FLAG_IMMUTABLE;
            }
        else
             alarmflags = 0;
        Intent alarmintent = new Intent(context, LossOfSensorAlarm.class);
        onalarm = getBroadcast(context, alarmrequest, alarmintent, alarmflags);
        }
    }
static    void setalarm(Context context, long alarmtime) {
    try {
        alarmtime+=20*1000;
        mkintents(context);
        scheduledAtMillis = alarmtime;
        {if(doLog) {Log.i(LOG_ID,"setalarm "+alarmtime);};};
        final int type=RTC_WAKEUP;

        AlarmManager manager= (AlarmManager) context.getSystemService(ALARM_SERVICE);


        // Not setAlarmClock, on Wear either: this alarm is re-armed on every reading and
        // every glucosetimeout, and a pending alarm clock keeps Doze from going idle.
        // Allow-while-idle still fires in Doze, so the watch keeps its periodic sync.
        manager.setExactAndAllowWhileIdle(type,alarmtime,onalarm);
       }
       catch(Throwable e) {
           Log.stack(LOG_ID,"setalarm", e);
           }
    finally {
        {if(doLog) {Log.i(LOG_ID,"after setalarm");};};
        }
    }

/**
 * As setalarm(), but an alarm still to come that goes off no later stays as it is.
 * For a re-arm from now that is not about a reading: each one would otherwise put
 * off the alarm the last reading armed, and with it what that alarm does for a
 * reading that does not come.
 */
static void setalarmNoLater(Context context, long alarmtime) {
    final long armed = scheduledAtMillis;
    if(LossAlarmAhead.keepsArmed(armed, alarmtime + 20*1000, System.currentTimeMillis())) {
        {if(doLog) {Log.i(LOG_ID,"setalarmNoLater keeps "+armed);};};
        return;
        }
    setalarm(context, alarmtime);
    }

static void cancelalarm() {
    if(onalarm!=null) {
        {if(doLog) {Log.i(LOG_ID,"cancelalarm");};};
        scheduledAtMillis = 0L;
        Notify.shownovalue();
        AlarmManager manager= (AlarmManager) Applic.app.getSystemService(ALARM_SERVICE);
        manager.cancel(onalarm);
        onalarm=null;//TODO: ?????
        }
    }
    }
