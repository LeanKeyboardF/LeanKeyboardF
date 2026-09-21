package com.EdS.LeanKeyboardF.utils;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import com.EdS.LeanKeyboardF.R;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "Legacy Android mode" (Settings -> Misc -> Advanced) for Android 4, 5
 * and 6 (API 14-23).
 *
 * <p>Off (the default): nothing in here is used, the keyboard runs exactly as
 * it always did.
 *
 * <p>On: the keyboard service avoids everything that only exists from
 * Android 7 (API 24) - and, for the UI, from Android 5 (API 21) - and uses an
 * older equivalent instead:
 * <ul>
 *   <li>{@link #sortEntriesByValue} - {@code Collections.sort} with a
 *       {@code Comparator} class instead of {@code List.sort()} /
 *       {@code Comparator.comparingInt()} (both API 24; on older systems they
 *       throw NoSuchMethodError the first time a suggestion is computed);</li>
 *   <li>{@code *_compat} layouts (see {@link #useCompatLayouts}) - PNG icons
 *       instead of vector drawables (API 21; inflating one on Android 4
 *       throws) and a plain state-list background instead of
 *       {@code ?android:attr/selectableItemBackgroundBorderless} (API 21);</li>
 *   <li>{@code ImageView.setColorFilter()} instead of
 *       {@code ImageView.setImageTintList()} (API 21) for the icon tint - see
 *       ThemeManager.</li>
 * </ul>
 *
 * <p>Everything used by the "on" path exists since API 1-14, so it is also
 * safe to switch it on on a modern device (the icons are then bitmaps and the
 * touch highlight is a plain colour instead of a ripple, nothing else
 * changes).
 */
public final class LegacyCompat {
    // Short tag: "Log tag exceeds limit of 23 characters" otherwise.
    private static final String TAG = "LbCompat";

    private LegacyCompat() {
    }

    public static boolean isEnabled(Context context) {
        return LeanKeyPreferences.instance(context).isLegacyAndroidCompatEnabled();
    }

    /**
     * Whether the keyboard view has to be inflated from the {@code *_compat}
     * layouts. Also false (with an error in the log) if the mode is on but
     * those resources cannot be resolved - the default layouts are then used,
     * as if the mode was off, instead of failing.
     */
    public static boolean useCompatLayouts(Context context) {
        return isEnabled(context) && areCompatResourcesAvailable(context);
    }

    /**
     * Start-up checks of the keyboard service. Nothing happens when the mode
     * is off. When it is on:
     * <ol>
     *   <li>the platform is logged (visible with Settings -> Misc ->
     *       Advanced -> Debug logging on, tag LbCompat);</li>
     *   <li>it is logged which fallbacks this particular Android version
     *       actually needs;</li>
     *   <li>the fallback resources are checked (see {@link #useCompatLayouts}).</li>
     * </ol>
     */
    public static void runStartupChecks(Context context) {
        if (!isEnabled(context)) {
            return;
        }

        int sdk = Build.VERSION.SDK_INT;

        LeanbackDebugLog.d(context, TAG, "Legacy Android mode is ON: Android " + Build.VERSION.RELEASE
                + " (API " + sdk + "), " + Build.MANUFACTURER + " " + Build.MODEL);

        if (sdk < 24) {
            LeanbackDebugLog.d(context, TAG, "API " + sdk + " < 24: no List.sort/Comparator.comparingInt, using Collections.sort");
        }

        if (sdk < 21) {
            LeanbackDebugLog.d(context, TAG, "API " + sdk + " < 21: no vector drawables/ripple/image tint, using PNG icons, state-list background and colour filter");
        }

        LeanbackDebugLog.d(context, TAG, "Fallback resources available: " + areCompatResourcesAvailable(context));
    }

    /**
     * Sorts by the map entry's value with API 1 calls only. Same result as
     * {@code entries.sort(...)} with a comparator on the value (descending:
     * highest first), for the case where {@code List.sort()} does not exist.
     */
    public static void sortEntriesByValue(List<Map.Entry<String, Integer>> entries, final boolean descending) {
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int first = descending ? b.getValue() : a.getValue();
                int second = descending ? a.getValue() : b.getValue();

                // Integer.compare() is API 19
                return first < second ? -1 : (first == second ? 0 : 1);
            }
        });
    }

    private static boolean areCompatResourcesAvailable(Context context) {
        Resources resources = context.getResources();
        int[] ids = {
                R.layout.root_leanback_compat,
                R.layout.root_leanback_floating_compat,
                R.layout.input_leanback_compat,
                R.layout.input_leanback_floating_compat,
                R.drawable.bg_action_button_compat,
                R.drawable.ic_action_clear_compat,
                R.drawable.ic_action_select_all_compat,
                R.drawable.ic_action_copy_compat,
                R.drawable.ic_action_cut_compat,
                R.drawable.ic_action_paste_compat
        };

        try {
            for (int id : ids) {
                resources.getResourceName(id);
            }

            return true;
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Legacy Android mode: fallback resources are missing, the default layouts are used", e);
            return false;
        }
    }
}
