/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.edgesuppression;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.RotationUtils;
import android.view.Display;
import android.view.Surface;
import android.view.View;

import com.xiaomi.settings.R;

/** Full-screen overlay that tints the regions currently sent to the touch firmware. */
final class EdgeSuppressionPreviewView extends View {
    private final int mPhysicalWidth;
    private final int mPhysicalHeight;
    private final Paint mPaint = new Paint();
    private final Path mPath = new Path();
    private final Rect mBounds = new Rect();
    private final DisplayMetrics mMetrics = new DisplayMetrics();
    private final int[] mLocation = new int[2];
    private int[] mRegions;

    EdgeSuppressionPreviewView(Context context, int physicalWidth, int physicalHeight) {
        super(context);
        mPhysicalWidth = physicalWidth;
        mPhysicalHeight = physicalHeight;
        mPaint.setColor(context.getColor(R.color.edge_suppression_preview_color));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setRegions(int[] regions) {
        mRegions = regions;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Display display = getDisplay();
        if (display == null || mRegions == null) return;

        int rotation = display.getRotation();
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int rotatedWidth = landscape ? mPhysicalHeight : mPhysicalWidth;
        int rotatedHeight = landscape ? mPhysicalWidth : mPhysicalHeight;
        display.getRealMetrics(mMetrics);
        getLocationOnScreen(mLocation);

        mPath.rewind();
        for (int i = 0; i + 5 < mRegions.length; i += 8) {
            mBounds.set(mRegions[i + 2], mRegions[i + 3], mRegions[i + 4], mRegions[i + 5]);
            if (mBounds.isEmpty()) continue;
            RotationUtils.rotateBounds(mBounds, mPhysicalWidth, mPhysicalHeight, rotation);
            mPath.addRect(mBounds.left, mBounds.top, mBounds.right, mBounds.bottom,
                    Path.Direction.CW);
        }

        canvas.save();
        canvas.translate(-mLocation[0], -mLocation[1]);
        canvas.scale((float) mMetrics.widthPixels / rotatedWidth,
                (float) mMetrics.heightPixels / rotatedHeight);
        canvas.drawPath(mPath, mPaint);
        canvas.restore();
    }
}
