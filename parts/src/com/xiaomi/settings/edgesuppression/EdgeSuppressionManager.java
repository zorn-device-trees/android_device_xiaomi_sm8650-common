/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.edgesuppression;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import androidx.preference.PreferenceManager;

import com.xiaomi.settings.R;
import com.xiaomi.settings.touch.TouchFeature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the edge suppression regions for the current rotation and sends them, together
 * with the rotation itself, to the touch firmware through the TouchFeature HAL.
 *
 * The firmware takes 12 regions of 8 ints each: type, position, top-left x/y,
 * bottom-right x/y, time, node. Types are corner (0), conditional (1) and absolute (2);
 * positions are top (0), bottom (1), left (2), right (3). Coordinates are physical panel
 * pixels in the natural (portrait) orientation.
 */
public final class EdgeSuppressionManager {
    private static final String TAG = "XiaomiPartsEdgeSuppression";

    static final String PREF_ENABLED = "edgesuppression_enable";
    static final String PREF_WIDTH = "edgesuppression_width_pixels";

    private static final int TYPE_CORNER = 0;
    private static final int TYPE_CONDITION = 1;
    private static final int TYPE_ABSOLUTE = 2;

    private static final int POS_TOP = 0;
    private static final int POS_BOTTOM = 1;
    private static final int POS_LEFT = 2;
    private static final int POS_RIGHT = 3;

    private static final int RECT_COUNT = 12;
    private static final int RECT_SIZE = 8;

    private static EdgeSuppressionManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final DisplayManager mDisplayManager;
    // TouchFeature calls can block while the HAL starts, keep them off the main thread.
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final int mMaxX;
    private final int mMaxY;
    private final int mAbsoluteWidth;
    private final int mDefaultWidth;
    private final int mMaxWidth;
    private final int[] mCorner;

    private int[] mRegions;
    private int mRegionIndex;

    private EdgeSuppressionManager(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);

        int width = 0;
        int height = 0;
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        for (Display.Mode mode : display.getSupportedModes()) {
            width = Math.max(width, mode.getPhysicalWidth());
            height = Math.max(height, mode.getPhysicalHeight());
        }
        mMaxX = Math.min(width, height) - 1;
        mMaxY = Math.max(width, height) - 1;

        Resources res = mContext.getResources();
        mAbsoluteWidth = res.getInteger(R.integer.edge_suppression_absolute_width);
        mDefaultWidth = res.getInteger(R.integer.edge_suppression_condition_default);
        mMaxWidth = res.getInteger(R.integer.edge_suppression_condition_max);
        mCorner = res.getIntArray(R.array.edge_suppression_corner);
    }

    public static synchronized EdgeSuppressionManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new EdgeSuppressionManager(context);
        }
        return sInstance;
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(PREF_ENABLED, false);
    }

    public int getWidth() {
        return Math.max(0, Math.min(mPrefs.getInt(PREF_WIDTH, mDefaultWidth), mMaxWidth));
    }

    int getPhysicalWidth() {
        return mMaxX + 1;
    }

    int getPhysicalHeight() {
        return mMaxY + 1;
    }

    /**
     * Sends the current rotation and regions to the touch firmware.
     *
     * @return the regions that were sent, for previewing
     */
    public synchronized int[] apply() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        boolean enabled = isEnabled();
        int[] regions = buildRegions(rotation, enabled ? getWidth() : 0, enabled);

        Log.d(TAG, "apply: rotation=" + rotation + " enabled=" + enabled);
        mExecutor.execute(() -> {
            TouchFeature.setModeValue(TouchFeature.MODE_ORIENTATION, rotation);
            TouchFeature.setModeLongValue(TouchFeature.MODE_EDGE_SUPPRESSION, regions);
        });
        return regions.clone();
    }

    private int[] buildRegions(int rotation, int width, boolean enabled) {
        mRegions = new int[RECT_COUNT * RECT_SIZE];
        mRegionIndex = 0;

        int absolute = width > 0 ? mAbsoluteWidth : 0;
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        if (landscape) {
            addEdges(TYPE_ABSOLUTE, absolute, true);
            addEdges(TYPE_CONDITION, width, true);
        } else {
            addEdges(TYPE_ABSOLUTE, absolute, false);
            addEdges(TYPE_CONDITION, width, false);
        }
        addCorners(rotation);

        if (!enabled) {
            // Keep each region's type and position but zero its bounds, so the
            // firmware clears it.
            for (int i = 0; i < RECT_COUNT; i++) {
                for (int j = 2; j < 6; j++) {
                    mRegions[i * RECT_SIZE + j] = 0;
                }
            }
        }
        return mRegions;
    }

    private void addEdges(int type, int width, boolean landscape) {
        if (landscape) {
            add(type, POS_TOP, 0, 0, mMaxX, width);
            add(type, POS_BOTTOM, 0, mMaxY - width, mMaxX, mMaxY);
        } else {
            add(type, POS_TOP, 0, 0, 0, 0);
            add(type, POS_BOTTOM, 0, 0, 0, 0);
        }
        add(type, POS_LEFT, 0, 0, width, mMaxY);
        add(type, POS_RIGHT, mMaxX - width, 0, mMaxX, mMaxY);
    }

    private void addCorners(int rotation) {
        int pw = mCorner[0];
        int ph = mCorner[1];
        int lw = mCorner[2];
        int lh = mCorner[3];
        switch (rotation) {
            case Surface.ROTATION_90:
                add(TYPE_CORNER, POS_TOP, 0, 0, lw, lh);
                add(TYPE_CORNER, POS_BOTTOM, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_LEFT, 0, mMaxY - lh, lw, mMaxY);
                add(TYPE_CORNER, POS_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_180:
                add(TYPE_CORNER, POS_TOP, 0, 0, pw, ph);
                add(TYPE_CORNER, POS_BOTTOM, mMaxX - pw, 0, mMaxX, ph);
                add(TYPE_CORNER, POS_LEFT, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_270:
                add(TYPE_CORNER, POS_TOP, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_BOTTOM, mMaxX - lw, 0, mMaxX, lh);
                add(TYPE_CORNER, POS_LEFT, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_RIGHT, mMaxX - lw, mMaxY - lh, mMaxX, mMaxY);
                break;
            case Surface.ROTATION_0:
            default:
                add(TYPE_CORNER, POS_TOP, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_BOTTOM, 0, 0, 0, 0);
                add(TYPE_CORNER, POS_LEFT, 0, mMaxY - ph, pw, mMaxY);
                add(TYPE_CORNER, POS_RIGHT, mMaxX - pw, mMaxY - ph, mMaxX, mMaxY);
                break;
        }
    }

    private void add(int type, int position, int left, int top, int right, int bottom) {
        int base = mRegionIndex++ * RECT_SIZE;
        mRegions[base] = type;
        mRegions[base + 1] = position;
        mRegions[base + 2] = left;
        mRegions[base + 3] = top;
        mRegions[base + 4] = right;
        mRegions[base + 5] = bottom;
        // base + 6 (time) and base + 7 (node) stay 0.
    }
}
