/*
 * SPDX-FileCopyrightText: 2023-2024 Paranoid Android
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Arrays;

/**
 * Minimal client for the stock vendor.xiaomi.hw.touchfeature AIDL service.
 *
 * Talks to the service with raw transactions so no interface library is needed. The
 * transaction codes and argument layouts come from BpTouchFeature in the stock
 * vendor.xiaomi.hw.touchfeature-V1-ndk.so:
 *   8 setModeLongValue(int touchId, int mode, int length, int[] values) -> int
 *   9 setModeValue(int touchId, int mode, int value) -> int
 */
public final class TouchFeature {
    private static final String TAG = "XiaomiPartsTouchFeature";

    private static final String DESCRIPTOR = "vendor.xiaomi.hw.touchfeature.ITouchFeature";
    private static final String SERVICE = DESCRIPTOR + "/default";

    private static final int TRANSACTION_SET_MODE_LONG_VALUE = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int TRANSACTION_SET_MODE_VALUE = IBinder.FIRST_CALL_TRANSACTION + 8;

    private static final int TOUCH_ID = 0;

    /** Display rotation (Surface.ROTATION_*). */
    public static final int MODE_ORIENTATION = 8;
    /** Edge suppression regions, 8 ints per region. */
    public static final int MODE_EDGE_SUPPRESSION = 15;

    private static IBinder sBinder;

    private TouchFeature() {}

    private static synchronized IBinder getBinder() {
        if (sBinder != null && sBinder.isBinderAlive()) {
            return sBinder;
        }
        sBinder = null;
        if (!ServiceManager.isDeclared(SERVICE)) {
            Log.e(TAG, SERVICE + " is not declared");
            return null;
        }
        IBinder binder = ServiceManager.waitForDeclaredService(SERVICE);
        if (binder != null) {
            sBinder = Binder.allowBlocking(binder);
        }
        return sBinder;
    }

    private static synchronized void reset() {
        sBinder = null;
    }

    public static boolean setModeValue(int mode, int value) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(DESCRIPTOR);
        data.writeInt(TOUCH_ID);
        data.writeInt(mode);
        data.writeInt(value);
        return transact(TRANSACTION_SET_MODE_VALUE, data, "setModeValue(" + mode + ", " + value + ")");
    }

    public static boolean setModeLongValue(int mode, int[] values) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(DESCRIPTOR);
        data.writeInt(TOUCH_ID);
        data.writeInt(mode);
        data.writeInt(values.length);
        data.writeIntArray(values);
        return transact(TRANSACTION_SET_MODE_LONG_VALUE, data,
                "setModeLongValue(" + mode + ", " + Arrays.toString(values) + ")");
    }

    private static boolean transact(int code, Parcel data, String what) {
        Parcel reply = Parcel.obtain();
        try {
            IBinder binder = getBinder();
            if (binder == null) {
                Log.e(TAG, what + ": service unavailable");
                return false;
            }
            if (!binder.transact(code, data, reply, 0)) {
                Log.e(TAG, what + ": transact failed");
                return false;
            }
            reply.readException();
            int ret = reply.readInt();
            if (ret != 0) {
                Log.w(TAG, what + " returned " + ret);
                return false;
            }
            return true;
        } catch (RemoteException | RuntimeException e) {
            reset();
            Log.e(TAG, what + " failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
