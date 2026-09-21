package com.EdS.LeanKeyboardF.activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import com.EdS.LeanKeyboardF.helpers.PermissionHelpers;
import com.EdS.LeanKeyboardF.receiver.RestartServiceReceiver;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;

public class PermissionsActivity extends FragmentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkPermissions();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // restart kbd service
        Intent intent = new Intent(this, RestartServiceReceiver.class);
        sendBroadcast(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        checkPermissions();
    }

    private void checkPermissions() {
        if (!PermissionHelpers.hasMicPermissions(this)) {
            PermissionHelpers.verifyMicPermissions(this);
        } else if (!PermissionHelpers.isStorageRequirementMet(this)) {
            // Mark it as asked BEFORE requesting: the result callback calls
            // checkPermissions() again, and on versions that auto-deny the
            // request it used to loop here forever.
            LeanKeyPreferences.instance(this).setStoragePermissionAsked(true);
            PermissionHelpers.verifyStoragePermissions(this);
        } else {
            finish();
        }
    }
}
