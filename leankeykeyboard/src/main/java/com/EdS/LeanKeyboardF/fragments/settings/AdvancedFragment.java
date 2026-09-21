package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.BuildConfig;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.R;

/**
 * Settings -> Misc -> Advanced. Holds the checkboxes that are for
 * troubleshooting rather than everyday use:
 * the debug-logging one and the "unstable releases" one (both moved here from
 * Misc, otherwise untouched) and "Legacy Android mode" (see LegacyCompat).
 */
public class AdvancedFragment extends BaseSettingsFragment {
    private LeanKeyPreferences mPrefs;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mPrefs = LeanKeyPreferences.instance(getActivity());

        // Moved from MiscFragment as is: same strings, same preference.
        addCheckedAction(R.string.debug_logging, R.string.debug_logging_desc, mPrefs::isDebugLoggingEnabled, mPrefs::setDebugLoggingEnabled);
        addCheckedAction(R.string.legacy_android_compat, R.string.legacy_android_compat_desc, mPrefs::isLegacyAndroidCompatEnabled, mPrefs::setLegacyAndroidCompatEnabled);

        // Moved from MiscFragment as is. Only meaningful in the origin
        // flavor - playstore has no GitHub-based "check for update" action
        // at all (see BuildConfig.SUPPORTS_SELF_UPDATE / UpdateChecker), so
        // showing this toggle there would control a feature that doesn't
        // exist.
        if (BuildConfig.SUPPORTS_SELF_UPDATE) {
            addCheckedAction(R.string.unstable_updates, R.string.unstable_updates_desc, mPrefs::isUnstableUpdatesEnabled, mPrefs::setUnstableUpdatesEnabled);
        }
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.advanced);
        String desc = getActivity().getResources().getString(R.string.advanced_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }
}
