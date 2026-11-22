/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.turbocharging;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.xiaomi.settings.R;
import com.xiaomi.settings.utils.FileUtils;

/**
 * Turbo charging: keeps the battery charge current limit at or above a floor.
 *
 * mi_thermald caps charging by writing battery/constant_charge_current from a PID loop on
 * the board temperature. While a charger is connected and turbo charging is on, this
 * service polls the limit and raises it back to the floor whenever the thermal daemon
 * set it lower. It never lowers the limit. When the battery reaches the cutoff
 * temperature it stops writing until the battery cools by HYSTERESIS, and the thermal
 * daemon takes over again. The ADSP's own JEITA protection is not affected.
 *
 * Polling runs on its own thread, since every poll does sysfs I/O. It uses uptime-based
 * delays, so it pauses while the system is suspended; mi_thermald is frozen then too, so
 * the limit cannot be lowered behind our back until both resume.
 */
public class TurboChargingService extends Service
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "XiaomiPartsTurboCharging";

    static final String PREF_ENABLED = "turbo_enabled";
    static final String PREF_CURRENT = "turbo_current";
    static final String PREF_TEMP_LIMIT = "turbo_temp_limit";
    static final String PREF_SPORT_MODE = "turbo_sport_mode";

    static final String FCC_NODE = "/sys/class/power_supply/battery/constant_charge_current";
    private static final String SPORT_MODE_NODE = "/sys/class/qcom-battery/sport_mode";

    // mi_thermald re-evaluates every 2 s; poll faster so the floor wins most of the time.
    private static final long POLL_INTERVAL_MS = 1000;
    // Resume 2 degrees C below the cutoff (values are tenths of a degree).
    static final int HYSTERESIS = 20;

    /** Read by the settings screen, written from the main and polling threads. */
    static volatile boolean sHot;

    private HandlerThread mPollThread;
    private Handler mPollHandler;
    private SharedPreferences mPrefs;

    // Receiver and preference callbacks run on the main thread; the poller only reads these.
    private boolean mPlugged;
    private volatile int mTemperature;
    private volatile boolean mPolling;
    // Bumped on every start/stop so a poll already in flight cannot keep the loop alive.
    private volatile int mPollGeneration;
    private boolean mSportModeSet;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            mTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            update();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mPollThread = new HandlerThread("TurboChargingPoll");
        mPollThread.start();
        mPollHandler = new Handler(mPollThread.getLooper());

        // The node may still be set from a previous run of this service that was killed
        // without cleaning up, so start from a known state.
        FileUtils.writeLine(SPORT_MODE_NODE, "0");

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPolling();
        setSportMode(false);
        unregisterReceiver(mBatteryReceiver);
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mPollThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key != null && key.startsWith("turbo_")) {
            update();
        }
    }

    static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    static long getFloor(Context context, SharedPreferences prefs) {
        return Long.parseLong(prefs.getString(PREF_CURRENT,
                context.getString(R.string.turbo_current_default)));
    }

    static int getTempLimit(Context context, SharedPreferences prefs) {
        return Integer.parseInt(prefs.getString(PREF_TEMP_LIMIT,
                context.getString(R.string.turbo_temp_default)));
    }

    private void update() {
        boolean active = isEnabled(mPrefs) && mPlugged;
        if (active) {
            updateHot();
            setSportMode(mPrefs.getBoolean(PREF_SPORT_MODE, false));
            startPolling();
        } else {
            stopPolling();
            setSportMode(false);
            sHot = false;
        }
    }

    private synchronized void updateHot() {
        int limit = getTempLimit(this, mPrefs);
        if (mTemperature >= limit) {
            if (!sHot) Log.i(TAG, "Battery at " + mTemperature + ", pausing");
            sHot = true;
        } else if (sHot && mTemperature <= limit - HYSTERESIS) {
            Log.i(TAG, "Battery at " + mTemperature + ", resuming");
            sHot = false;
        }
    }

    private void enforceFloor() {
        updateHot();
        if (sHot) return;
        long floor = getFloor(this, mPrefs);
        long current = FileUtils.readLong(FCC_NODE, -1);
        if (current >= 0 && current < floor) {
            FileUtils.writeLine(FCC_NODE, String.valueOf(floor));
        }
    }

    private void startPolling() {
        if (mPolling) return;
        Log.i(TAG, "Charger connected, enforcing floor");
        mPolling = true;
        final int generation = ++mPollGeneration;
        mPollHandler.post(() -> poll(generation));
    }

    private void poll(int generation) {
        if (generation != mPollGeneration) return;
        enforceFloor();
        mPollHandler.postDelayed(() -> poll(generation), POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        if (!mPolling) return;
        Log.i(TAG, "Stopped enforcing floor");
        mPolling = false;
        mPollGeneration++;
        mPollHandler.removeCallbacksAndMessages(null);
    }

    private void setSportMode(boolean enabled) {
        if (enabled == mSportModeSet) return;
        if (FileUtils.writeLine(SPORT_MODE_NODE, enabled ? "1" : "0")) {
            mSportModeSet = enabled;
        }
    }
}
