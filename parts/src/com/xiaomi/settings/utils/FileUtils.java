/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class FileUtils {
    private static final String TAG = "XiaomiPartsFileUtils";

    private FileUtils() {}

    /** Returns the first line of the file, or null if it can't be read. */
    public static String readLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + path, e);
            return null;
        }
    }

    /** Returns the file's integer value, or def if it can't be read or parsed. */
    public static long readLong(String path, long def) {
        String line = readLine(path);
        if (line == null) return def;
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean writeLine(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + value + " to " + path, e);
            return false;
        }
    }
}
