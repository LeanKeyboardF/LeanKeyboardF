package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.activity.settings.KbActivationActivity;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.R;

public class KbSettingsFragment extends BaseSettingsFragment {
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        LeanKeyPreferences prefs = LeanKeyPreferences.instance(context);

        addNextAction(R.string.activate_keyboard, () -> !prefs.isActivateKeyboardHidden(), () -> {
            Intent intent = new Intent(getActivity(), KbActivationActivity.class);
            startActivity(intent);
        });

        addNextAction(R.string.change_layout, () -> startGuidedFragment(new KbLayoutFragment()));

        addNextAction(R.string.interface_settings, () -> startGuidedFragment(new KbInterfaceFragment()));

        addNextAction(R.string.suggestions_settings, () -> startGuidedFragment(new KbSuggestionsFragment()));

        addNextAction(R.string.clipboard_buffer, () -> startGuidedFragment(new KbClipboardFragment()));

        addNextAction(R.string.misc, () -> startGuidedFragment(new MiscFragment()));

        addNextAction(R.string.about_desc, () -> startGuidedFragment(new AboutFragment()));
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.ime_name);
        String desc = getActivity().getResources().getString(R.string.kb_settings_desc);
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
