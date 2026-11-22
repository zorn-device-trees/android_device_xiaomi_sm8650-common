/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.collapsingtoolbar.R;

public class ThermalSettingsActivity extends CollapsingToolbarBaseActivity {
    private static final String TAG = "thermal";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportFragmentManager().findFragmentByTag(TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new ThermalSettingsFragment(), TAG)
                    .commit();
        }
    }
}
