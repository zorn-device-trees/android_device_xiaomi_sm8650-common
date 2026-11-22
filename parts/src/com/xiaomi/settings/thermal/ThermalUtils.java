/*
 * SPDX-FileCopyrightText: 2020 The LineageOS Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.android.settingslib.applications.AppUtils;

import com.xiaomi.settings.R;
import com.xiaomi.settings.utils.FileUtils;

/**
 * Per-app thermal profiles. Selects one of the stock mi_thermald scene configs by writing
 * its index (from vendor/etc/thermal-map.conf) to thermal_message/sconfig.
 */
public final class ThermalUtils {
    private static final String TAG = "XiaomiPartsThermal";

    static final String PREF_ENABLED = "thermal_enabled";
    private static final String PREF_PROFILE_PREFIX = "thermal_profile_";

    private static final String SCONFIG = "/sys/class/thermal/thermal_message/sconfig";

    /** No manual choice: derive the profile from the app's category. */
    static final int PROFILE_AUTO = -1;

    static final int PROFILE_DEFAULT = 0;
    static final int PROFILE_BENCHMARK = 1;
    static final int PROFILE_BROWSER = 2;
    static final int PROFILE_CAMERA = 3;
    static final int PROFILE_DIALER = 4;
    static final int PROFILE_GAMING = 5;
    static final int PROFILE_NAVIGATION = 6;
    static final int PROFILE_STREAMING = 7;
    static final int PROFILE_VIDEO = 8;

    /** Profile names, indexed by PROFILE_*. */
    static final int[] PROFILE_NAMES = {
        R.string.thermal_default,
        R.string.thermal_benchmark,
        R.string.thermal_browser,
        R.string.thermal_camera,
        R.string.thermal_dialer,
        R.string.thermal_gaming,
        R.string.thermal_navigation,
        R.string.thermal_streaming,
        R.string.thermal_video,
    };

    private static final String GMAPS_PACKAGE = "com.google.android.apps.maps";
    private static final String GMEET_PACKAGE = "com.google.android.apps.tachyon";

    private static ThermalUtils sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final int[] mSconfig;

    private ThermalUtils(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mSconfig = mContext.getResources().getIntArray(R.array.thermal_profile_sconfig);
    }

    public static synchronized ThermalUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ThermalUtils(context);
        }
        return sInstance;
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(PREF_ENABLED, true);
    }

    void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            startService();
        } else {
            mContext.stopService(new Intent(mContext, ThermalService.class));
            applyProfile(PROFILE_DEFAULT);
        }
    }

    public void startService() {
        if (isEnabled()) {
            mContext.startServiceAsUser(new Intent(mContext, ThermalService.class),
                    UserHandle.CURRENT);
        }
    }

    /** Returns the manual profile for the package, or PROFILE_AUTO. */
    int getManualProfile(String packageName) {
        return mPrefs.getInt(PREF_PROFILE_PREFIX + packageName, PROFILE_AUTO);
    }

    void setManualProfile(String packageName, int profile) {
        if (profile == PROFILE_AUTO) {
            mPrefs.edit().remove(PREF_PROFILE_PREFIX + packageName).apply();
        } else {
            mPrefs.edit().putInt(PREF_PROFILE_PREFIX + packageName, profile).apply();
        }
    }

    int getProfileForPackage(String packageName) {
        int profile = getManualProfile(packageName);
        return profile != PROFILE_AUTO ? profile : getAutoProfile(packageName);
    }

    void applyProfileForPackage(String packageName) {
        applyProfile(TextUtils.isEmpty(packageName)
                ? PROFILE_DEFAULT : getProfileForPackage(packageName));
    }

    // Always written, never cached: mi_thermald can change sconfig on its own (e.g. for the
    // camera), so a remembered value says nothing about what the node holds now.
    synchronized void applyProfile(int profile) {
        int sconfig = mSconfig[profile];
        Log.d(TAG, "profile " + profile + " -> sconfig " + sconfig);
        FileUtils.writeLine(SCONFIG, String.valueOf(sconfig));
    }

    int getAutoProfile(String packageName) {
        switch (packageName) {
            case GMAPS_PACKAGE:
                return PROFILE_NAVIGATION;
            case GMEET_PACKAGE:
                return PROFILE_STREAMING;
        }

        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return PROFILE_DEFAULT;
        }

        switch (appInfo.category) {
            case ApplicationInfo.CATEGORY_GAME:
                return PROFILE_GAMING;
            case ApplicationInfo.CATEGORY_VIDEO:
                return PROFILE_VIDEO;
            case ApplicationInfo.CATEGORY_MAPS:
                return PROFILE_NAVIGATION;
        }

        final TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        if (AppUtils.isBrowserApp(mContext, packageName, UserHandle.myUserId())) {
            return PROFILE_BROWSER;
        } else if (telecomManager != null
                && packageName.equals(telecomManager.getDefaultDialerPackage())) {
            return PROFILE_DIALER;
        } else if (isCameraApp(packageName)) {
            return PROFILE_CAMERA;
        }
        return PROFILE_DEFAULT;
    }

    private boolean isCameraApp(String packageName) {
        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .setPackage(packageName);
        return !mContext.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_ALL).isEmpty();
    }
}
