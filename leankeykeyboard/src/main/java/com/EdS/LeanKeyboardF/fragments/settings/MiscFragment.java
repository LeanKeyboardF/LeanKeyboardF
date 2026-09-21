package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.activity.settings.KbSettingsActivity2;
import com.EdS.LeanKeyboardF.helpers.Helpers;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.R;

public class MiscFragment extends BaseSettingsFragment {
    private LeanKeyPreferences mPrefs;
    private Context mContext;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mContext = context;
        mPrefs = LeanKeyPreferences.instance(getActivity());
        addCheckedAction(R.string.keep_on_screen, R.string.keep_on_screen_desc, mPrefs::getForceShowKeyboard, mPrefs::setForceShowKeyboard);
        addCheckedAction(R.string.show_launcher_icon, R.string.show_launcher_icon_desc, this::getLauncherIconShown, this::setLauncherIconShown);
        addCheckedAction(R.string.enable_cyclic_navigation, R.string.enable_cyclic_navigation_desc, mPrefs::isCyclicNavigationEnabled, mPrefs::setCyclicNavigationEnabled);
        addCheckedAction(R.string.physical_keyboard_mode, R.string.physical_keyboard_mode_desc, mPrefs::isPhysicalKeyboardMode, mPrefs::setPhysicalKeyboardMode);

        // Sub-section with the debug-logging, "Legacy Android mode" and
        // "unstable releases" checkboxes.
        addNextAction(R.string.advanced, () -> startGuidedFragment(new AdvancedFragment()));
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.misc);
        String desc = getActivity().getResources().getString(R.string.misc_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }

    private void startGuidedFragment(GuidedStepSupportFragment fragment) {
        if (getFragmentManager() != null) {
            GuidedStepSupportFragment.add(getFragmentManager(), fragment);
        }
    }

    private void setLauncherIconShown(boolean shown) {
        Helpers.setLauncherIconShown(mContext, KbSettingsActivity2.class, shown);
    }

    private boolean getLauncherIconShown() {
        return Helpers.getLauncherIconShown(mContext, KbSettingsActivity2.class);
    }
}
