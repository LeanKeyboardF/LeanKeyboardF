package com.EdS.LeanKeyboardF.utils;

import android.content.Context;
import android.util.Log;

/**
 * Single on/off switch for every verbose Log.d() trace scattered across the
 * ime package (key focus moves, physical-keyboard state, service lifecycle,
 * etc). Off by default (see {@link LeanKeyPreferences#isDebugLoggingEnabled()}),
 * toggled from Settings -> Misc -> Advanced -> "Debug logging".
 *
 * See README "Debug logging" section for the matching adb logcat command.
 */
public final class LeanbackDebugLog {
    private LeanbackDebugLog() {
    }

    public static boolean isEnabled(Context context) {
        return LeanKeyPreferences.instance(context).isDebugLoggingEnabled();
    }

    public static void d(Context context, String tag, String message) {
        if (isEnabled(context)) {
            Log.d(tag, message);
        }
    }
}
