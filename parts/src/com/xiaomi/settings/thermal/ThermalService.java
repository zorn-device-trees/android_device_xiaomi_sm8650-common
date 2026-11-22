/*
 * SPDX-FileCopyrightText: 2020 The LineageOS Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/** Applies the foreground app's thermal profile, and the default one while the screen is off. */
public class ThermalService extends Service {
    private static final String TAG = "XiaomiPartsThermalService";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ThermalUtils mThermalUtils;
    private boolean mScreenOn = true;
    private String mForegroundApp = "";

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            update();
        }
    };

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // Binder thread: hop to the main thread before touching state.
            mHandler.post(ThermalService.this::onTaskStackChangedLocked);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mThermalUtils = ThermalUtils.getInstance(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register task stack listener", e);
        }
        onTaskStackChangedLocked();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mScreenReceiver);
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
        } catch (RemoteException e) {
            // Ignore
        }
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onTaskStackChangedLocked() {
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) return;
            ComponentName top = info.topActivity;
            if (!top.getPackageName().equals(mForegroundApp)) {
                mForegroundApp = top.getPackageName();
                update();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get the focused task", e);
        }
    }

    private void update() {
        if (mScreenOn) {
            mThermalUtils.applyProfileForPackage(mForegroundApp);
        } else {
            mThermalUtils.applyProfile(ThermalUtils.PROFILE_DEFAULT);
        }
    }
}
