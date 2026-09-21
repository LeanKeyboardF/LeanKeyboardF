package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import androidx.leanback.widget.GuidedAction;
import com.EdS.LeanKeyboardF.helpers.Helpers;
import com.EdS.LeanKeyboardF.helpers.MessageHelpers;
import com.EdS.LeanKeyboardF.R;

import java.util.List;

public class AboutFragment extends GuidedStepSupportFragment {
    private static final String ORIGINAL_URL = "https://github.com/yuliskov/LeanKeyboard";
    private static final String THIS_URL = "https://github.com/AmakerGame/LeanKeyboardF/tree/master";
    private static final String[] URL_MAPPING = {ORIGINAL_URL, THIS_URL};

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.about);
        String desc = getActivity().getResources().getString(R.string.about_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        appendInfoAction(getString(R.string.about_original), actions);
        appendInfoAction(getString(R.string.about_this_program), actions);
    }

    private void appendInfoAction(String textLine, List<GuidedAction> actions) {
        GuidedAction action = new GuidedAction.Builder(getActivity())
                .title(textLine)
                .id(actions.size())
                .build();
        actions.add(action);
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        int idx = (int) action.getId();
        String link = URL_MAPPING.length > idx ? URL_MAPPING[idx] : THIS_URL;

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));

        // Many Android TV boxes have no browser at all - startActivity()
        // would throw ActivityNotFoundException. Show the address instead
        // so it can still be typed in on another device.
        if (!Helpers.startIntent(getActivity(), intent)) {
            MessageHelpers.showLongMessage(getActivity(), link);
        }
    }
}
