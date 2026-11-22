/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.edgesuppression;

import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

/**
 * Keeps the touch firmware's rotation and edge suppression regions in sync with the
 * display. The rotation is sent even when edge suppression is off.
 */
public class EdgeSuppressionService extends Service {
    private EdgeSuppressionManager mManager;
    private DisplayManager mDisplayManager;
    private int mRotation = -1;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) return;
            Display display = mDisplayManager.getDisplay(displayId);
            if (display != null && display.getRotation() != mRotation) {
                apply();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mManager = EdgeSuppressionManager.getInstance(this);
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        apply();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void apply() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) {
            mRotation = display.getRotation();
        }
        mManager.apply();
    }
}
