/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.edgesuppression;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.collapsingtoolbar.R;

public class EdgeSuppressionSettingsActivity extends CollapsingToolbarBaseActivity {
    private static final String TAG = "edgesuppression";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportFragmentManager().findFragmentByTag(TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new EdgeSuppressionSettingsFragment(), TAG)
                    .commit();
        }
    }
}
