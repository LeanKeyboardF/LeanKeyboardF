package com.EdS.LeanKeyboardF.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.EdS.LeanKeyboardF.ime.LeanbackImeService;

public class RestartServiceReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTART_SERVICE =
            "com.EdS.LeanKeyboardF.action.RESTART_SERVICE";
    private static final String TAG = RestartServiceReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!ACTION_RESTART_SERVICE.equals(action)
                && !Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        sendMessageToService(context);
    }

    private void sendMessageToService(Context context) {
        Log.d(TAG, "Sending restart message to the service");
        Intent intent = new Intent(context, LeanbackImeService.class);
        intent.putExtra(LeanbackImeService.COMMAND_RESTART, true);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            // Package updates can arrive while Android restricts background
            // service starts. A later IME start will initialize it normally.
            Log.w(TAG, "Unable to restart the keyboard service in background", e);
        }
    }
}
