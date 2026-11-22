/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.turbocharging;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.Preference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import com.xiaomi.settings.R;
import com.xiaomi.settings.utils.FileUtils;

import java.util.Locale;

public class TurboChargingFragment extends SettingsBasePreferenceFragment {

    private static final String KEY_STATUS = "turbo_status";
    private static final long STATUS_INTERVAL_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Preference mStatus;

    private final Runnable mStatusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mHandler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.turbo_charging, rootKey);
        mStatus = findPreference(KEY_STATUS);

        MainSwitchPreference enable = findPreference(TurboChargingService.PREF_ENABLED);
        enable.setOnPreferenceChangeListener((pref, value) -> {
            mHandler.post(this::updateStatus);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mStatusUpdater);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mStatusUpdater);
        super.onPause();
    }

    private void updateStatus() {
        Context context = requireContext();
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        boolean plugged = battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int temp = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        String tempText = String.format(Locale.getDefault(), "%.1f °C", temp / 10f);

        if (!TurboChargingService.isEnabled(prefs)) {
            mStatus.setTitle(R.string.turbo_status_disabled);
        } else if (!plugged) {
            mStatus.setTitle(R.string.turbo_status_idle);
        } else if (TurboChargingService.sHot) {
            mStatus.setTitle(getString(R.string.turbo_status_hot, tempText));
        } else {
            long fcc = FileUtils.readLong(TurboChargingService.FCC_NODE, -1);
            String fccText = fcc < 0 ? "?"
                    : String.format(Locale.getDefault(), "%.1f A", fcc / 1000000f);
            mStatus.setTitle(getString(R.string.turbo_status_active, fccText, tempText));
        }
    }
}
