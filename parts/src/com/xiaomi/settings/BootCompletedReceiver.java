/*
 * Copyright (C) 2023 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

import com.xiaomi.settings.display.ColorModeService;
import com.xiaomi.settings.edgesuppression.EdgeSuppressionService;
import com.xiaomi.settings.thermal.ThermalUtils;
import com.xiaomi.settings.turbocharging.TurboChargingService;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "XiaomiParts";
    private static final boolean DEBUG = true;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            return;
        }
        if (DEBUG)
            Log.d(TAG, "Received boot completed intent");

        // Start the display color mode service
        context.startServiceAsUser(new Intent(context, ColorModeService.class),
                UserHandle.CURRENT);

        // Touch firmware rotation and edge suppression
        context.startServiceAsUser(new Intent(context, EdgeSuppressionService.class),
                UserHandle.CURRENT);

        // Per-app thermal profiles (only starts when enabled)
        ThermalUtils.getInstance(context).startService();

        // Turbo charging (idles unless enabled and plugged in)
        context.startServiceAsUser(new Intent(context, TurboChargingService.class),
                UserHandle.CURRENT);
    }
}
