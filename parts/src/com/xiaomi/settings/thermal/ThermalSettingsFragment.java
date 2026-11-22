/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import com.xiaomi.settings.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThermalSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_APPS = "thermal_apps";
    private static final int ICON_SIZE_DP = 36;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ThermalUtils mThermalUtils;
    private MainSwitchPreference mSwitch;
    private PreferenceCategory mApps;
    private CharSequence[] mProfileNames;

    private static final class AppInfo {
        String packageName;
        CharSequence label;
        Drawable icon;
        int autoProfile;
    }

    // Package name -> app, for summaries after a change.
    private final Map<String, AppInfo> mAppMap = new HashMap<>();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.thermal_settings, rootKey);
        mThermalUtils = ThermalUtils.getInstance(requireContext());

        mProfileNames = new CharSequence[ThermalUtils.PROFILE_NAMES.length];
        for (int i = 0; i < mProfileNames.length; i++) {
            mProfileNames[i] = getString(ThermalUtils.PROFILE_NAMES[i]);
        }

        mSwitch = findPreference(ThermalUtils.PREF_ENABLED);
        mSwitch.setPersistent(false);
        mSwitch.setChecked(mThermalUtils.isEnabled());
        mSwitch.setOnPreferenceChangeListener(this);

        mApps = findPreference(KEY_APPS);
        mApps.setVisible(mThermalUtils.isEnabled());
    }

    @Override
    public void onResume() {
        super.onResume();
        loadApps();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mSwitch) {
            boolean enabled = (Boolean) newValue;
            mThermalUtils.setEnabled(enabled);
            mApps.setVisible(enabled);
            return true;
        }

        AppInfo app = mAppMap.get(preference.getKey());
        if (app == null) return false;
        mThermalUtils.setManualProfile(app.packageName, Integer.parseInt((String) newValue));
        preference.setSummary(getProfileSummary(app));
        return true;
    }

    private void loadApps() {
        final Context context = requireContext().getApplicationContext();
        final ThermalUtils utils = mThermalUtils;
        final int iconSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                ICON_SIZE_DP, getResources().getDisplayMetrics()));
        mExecutor.execute(() -> {
            List<AppInfo> apps = queryLauncherApps(context, utils, iconSize);
            mHandler.post(() -> {
                if (isAdded()) populate(apps);
            });
        });
    }

    private static List<AppInfo> queryLauncherApps(Context context, ThermalUtils utils,
            int iconSize) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<AppInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (!seen.add(ai.packageName) || ai.packageName.equals(context.getPackageName())) {
                continue;
            }
            AppInfo app = new AppInfo();
            app.packageName = ai.packageName;
            app.label = ai.loadLabel(pm);
            app.icon = scaleIcon(context.getResources(), ai.loadIcon(pm), iconSize);
            app.autoProfile = utils.getAutoProfile(ai.packageName);
            apps.add(app);
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }

    private static Drawable scaleIcon(Resources res, Drawable icon, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        icon.setBounds(0, 0, size, size);
        icon.draw(canvas);
        return new BitmapDrawable(res, bitmap);
    }

    private void populate(List<AppInfo> apps) {
        Context context = getPreferenceManager().getContext();

        CharSequence[] values = new CharSequence[mProfileNames.length + 1];
        values[0] = String.valueOf(ThermalUtils.PROFILE_AUTO);
        for (int i = 0; i < mProfileNames.length; i++) {
            values[i + 1] = String.valueOf(i);
        }

        mApps.removeAll();
        mAppMap.clear();
        for (AppInfo app : apps) {
            mAppMap.put(app.packageName, app);
            CharSequence[] entries = new CharSequence[values.length];
            entries[0] = getString(R.string.thermal_auto, mProfileNames[app.autoProfile]);
            System.arraycopy(mProfileNames, 0, entries, 1, mProfileNames.length);

            ListPreference pref = new ListPreference(context);
            pref.setKey(app.packageName);
            pref.setPersistent(false);
            pref.setTitle(app.label);
            pref.setDialogTitle(app.label);
            pref.setIcon(app.icon);
            pref.setEntries(entries);
            pref.setEntryValues(values);
            pref.setValue(String.valueOf(mThermalUtils.getManualProfile(app.packageName)));
            pref.setSummary(getProfileSummary(app));
            pref.setOnPreferenceChangeListener(this);
            mApps.addPreference(pref);
        }
    }

    private CharSequence getProfileSummary(AppInfo app) {
        int manual = mThermalUtils.getManualProfile(app.packageName);
        if (manual != ThermalUtils.PROFILE_AUTO) {
            return mProfileNames[manual];
        }
        return getString(R.string.thermal_auto, mProfileNames[app.autoProfile]);
    }
}
