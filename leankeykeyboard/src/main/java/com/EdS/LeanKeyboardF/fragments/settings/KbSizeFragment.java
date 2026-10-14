package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.R;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;

// Interface -> Size: shows the current keyboard size with Increase and
// Decrease below it. A button is hidden when its limit is reached, and the
// list is refreshed after every click so the label follows the change.
public class KbSizeFragment extends BaseSettingsFragment {
    private LeanKeyPreferences mPrefs;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mPrefs = LeanKeyPreferences.instance(context);

        addInfoAction(
                () -> getString(R.string.current_size, getSizeLabel()),
                () -> getString(R.string.keyboard_size_hint)
        );

        addNextAction(R.string.increase_size,
                () -> mPrefs.getKeyboardSizeLevel() < LeanKeyPreferences.MAX_KEYBOARD_SIZE_LEVEL,
                () -> {
                    mPrefs.increaseKeyboardSize();
                    refreshActions();
                });

        addNextAction(R.string.decrease_size,
                () -> mPrefs.getKeyboardSizeLevel() > 0,
                () -> {
                    mPrefs.decreaseKeyboardSize();
                    refreshActions();
                });
    }

    private String getSizeLabel() {
        switch (mPrefs.getKeyboardSizeLevel()) {
            case 1: return getString(R.string.size_large);
            case 2: return getString(R.string.size_xlarge);
            default: return getString(R.string.size_normal);
        }
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.keyboard_size);
        String desc = getActivity().getResources().getString(R.string.keyboard_size_screen_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }
}
