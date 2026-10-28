package com.EdS.LeanKeyboardF.activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import com.EdS.LeanKeyboardF.helpers.PermissionHelpers;
import com.EdS.LeanKeyboardF.receiver.RestartServiceReceiver;

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
        intent.setAction(RestartServiceReceiver.ACTION_RESTART_SERVICE);
        sendBroadcast(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PermissionHelpers.REQUEST_MIC
                && !PermissionHelpers.hasMicPermissions(this)) {
            // Do not immediately show the same system prompt again after a
            // denial. The user can retry by invoking voice input again.
            finish();
            return;
        }

        checkPermissions();
    }

    private void checkPermissions() {
        if (!PermissionHelpers.hasMicPermissions(this)) {
            PermissionHelpers.verifyMicPermissions(this);
        } else {
            finish();
        }
    }
}
