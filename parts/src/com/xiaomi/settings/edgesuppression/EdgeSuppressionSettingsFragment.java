/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.edgesuppression;

import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import com.xiaomi.settings.R;

public class EdgeSuppressionSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private EdgeSuppressionManager mManager;
    private DisplayManager mDisplayManager;
    private MainSwitchPreference mSwitch;
    private SeekBarPreference mWidth;
    private EdgeSuppressionPreviewView mPreview;
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
                update();
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.edge_suppression, rootKey);
        mManager = EdgeSuppressionManager.getInstance(requireContext());
        mDisplayManager = requireContext().getSystemService(DisplayManager.class);

        mSwitch = findPreference(EdgeSuppressionManager.PREF_ENABLED);
        mSwitch.setChecked(mManager.isEnabled());
        mSwitch.setOnPreferenceChangeListener(this);

        mWidth = findPreference(EdgeSuppressionManager.PREF_WIDTH);
        mWidth.setUpdatesContinuously(true);
        mWidth.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewGroup decor = (ViewGroup) requireActivity().getWindow().getDecorView();
        mPreview = new EdgeSuppressionPreviewView(requireContext(),
                mManager.getPhysicalWidth(), mManager.getPhysicalHeight());
        decor.addView(mPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onStart() {
        super.onStart();
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
        update();
    }

    @Override
    public void onStop() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (mPreview != null && mPreview.getParent() instanceof ViewGroup parent) {
            parent.removeView(mPreview);
        }
        mPreview = null;
        super.onDestroyView();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // The listener runs before the new value is persisted, so store it first.
        if (preference == mSwitch) {
            getPreferenceManager().getSharedPreferences().edit()
                    .putBoolean(EdgeSuppressionManager.PREF_ENABLED, (Boolean) newValue)
                    .apply();
        } else if (preference == mWidth) {
            getPreferenceManager().getSharedPreferences().edit()
                    .putInt(EdgeSuppressionManager.PREF_WIDTH, (Integer) newValue)
                    .apply();
        } else {
            return false;
        }
        update();
        return true;
    }

    private void update() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) {
            mRotation = display.getRotation();
        }
        int[] regions = mManager.apply();
        if (mPreview != null) {
            mPreview.setRegions(regions);
        }
    }
}
