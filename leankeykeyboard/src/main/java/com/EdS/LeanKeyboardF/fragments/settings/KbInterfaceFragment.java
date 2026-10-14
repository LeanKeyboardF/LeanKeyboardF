package com.EdS.LeanKeyboardF.fragments.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.R;

public class KbInterfaceFragment extends BaseSettingsFragment {
    private LeanKeyPreferences mPrefs;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mPrefs = LeanKeyPreferences.instance(getActivity());

        addCheckedAction(R.string.floating_keyboard, R.string.floating_keyboard_desc,
                mPrefs::isFloatingKeyboard, this::onFloatingKeyboardToggled);

        addNextAction(R.string.change_theme, () -> startGuidedFragment(new KbThemeFragment()));

        // Current size and Increase/Decrease live on their own screen.
        addNextAction(R.string.keyboard_size, () -> startGuidedFragment(new KbSizeFragment()));

        // Only offered once the floating keyboard has actually been moved.
        addNextAction(R.string.reset_floating_position, () -> mPrefs.isFloatingMoved(), this::onResetFloatingPosition);

        // Hides the "Activate Keyboard" row on the main Settings screen
        // (see KbSettingsFragment).
        addCheckedAction(R.string.hide_activate_keyboard, R.string.hide_activate_keyboard_desc,
                mPrefs::isActivateKeyboardHidden, mPrefs::setActivateKeyboardHidden);
    }

    // Turning the floating keyboard OFF applies immediately, no prompt.
    // Turning it ON is gated behind a confirmation, since it's still an
    // experimental feature - Cancel (or Back) reverts the checkbox back to
    // unchecked without changing the preference.
    private void onFloatingKeyboardToggled(boolean checked) {
        if (!checked) {
            mPrefs.setFloatingKeyboard(false);
            refreshActions(); // hides "Reset position"
            showAppliedHint();
            return;
        }

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.floating_keyboard_warning_title)
                .setMessage(R.string.floating_keyboard_warning_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mPrefs.setFloatingKeyboard(true);
                    refreshActions(); // also shows "Reset position" if needed
                    showAppliedHint();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> revertFloatingKeyboard())
                // Back used to do nothing (the dialog was not cancelable).
                .setOnCancelListener(dialog -> revertFloatingKeyboard())
                .show();
    }

    private void revertFloatingKeyboard() {
        mPrefs.setFloatingKeyboard(false);
        refreshCheckedActions();
    }

    // The keyboard reads this setting when it is shown, so nothing visibly
    // changes here - say so, instead of leaving people wondering.
    private void showAppliedHint() {
        Toast.makeText(getActivity(), R.string.floating_keyboard_applies_hint, Toast.LENGTH_LONG).show();
    }

    private void onResetFloatingPosition() {
        mPrefs.resetFloatingOffset();
        refreshActions();
        Toast.makeText(getActivity(), R.string.floating_position_reset, Toast.LENGTH_SHORT).show();
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.interface_settings);
        String desc = getActivity().getResources().getString(R.string.interface_settings_desc);
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
}
