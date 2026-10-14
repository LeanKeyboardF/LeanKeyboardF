package com.EdS.LeanKeyboardF.fragments.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.GuidanceStylist.Guidance;
import com.EdS.LeanKeyboardF.helpers.MessageHelpers;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.utils.SystemClipboard;
import com.EdS.LeanKeyboardF.R;

public class KbClipboardFragment extends BaseSettingsFragment {
    private LeanKeyPreferences mPrefs;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mPrefs = LeanKeyPreferences.instance(getActivity());

        addCheckedAction(R.string.clipboard_sync, R.string.clipboard_sync_desc,
                mPrefs::isClipboardSyncEnabled, mPrefs::setClipboardSyncEnabled);

        addNextAction(R.string.clear_clipboard_buffer, this::onClearClicked);
    }

    private void onClearClicked() {
        mPrefs.clearClipboardHistory();

        // With sync on the history is rebuilt from the system clipboard, so
        // its current content must go too, or the last item would come back.
        if (mPrefs.isClipboardSyncEnabled()) {
            SystemClipboard.clear(getActivity());
        }

        MessageHelpers.showMessage(getActivity(), R.string.clear_clipboard_buffer_message);

        popBackStackToGuidedStepSupportFragment(KbSettingsFragment.class, 0);
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getActivity().getResources().getString(R.string.clipboard_buffer);
        String desc = getActivity().getResources().getString(R.string.clipboard_buffer_desc);
        Drawable icon = ContextCompat.getDrawable(getActivity(), R.drawable.ic_launcher);

        return new Guidance(
                title,
                desc,
                "",
                icon
        );
    }
}
