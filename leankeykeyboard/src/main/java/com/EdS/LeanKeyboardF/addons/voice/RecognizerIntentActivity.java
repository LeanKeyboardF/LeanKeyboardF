package com.EdS.LeanKeyboardF.addons.voice;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class RecognizerIntentActivity extends AppCompatActivity {
    public static RecognizerCallback sCallback;
    private VoiceSearchBridge mBridge;
    private RecognizerCallback mCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The activity may be restored after process death, while this
        // callback's static owner has already been cleared. Close cleanly
        // instead of opening a recognizer that would crash on its result.
        mCallback = sCallback;
        if (mCallback == null) {
            finish();
            return;
        }

        mBridge = new VoiceSearchBridge(this, mCallback::openSearchPage);

        mBridge.displaySpeechRecognizers();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (mBridge != null) {
            mBridge.onActivityResult(requestCode, resultCode, data);
        }

        clearPendingCallback();
        finish();
    }

    @Override
    protected void onDestroy() {
        // Keep the process-local callback when Android recreates this Activity
        // for a configuration change; the replacement instance will reattach
        // it in onCreate. Finishing (including user cancellation) releases it.
        if (isFinishing()) {
            clearPendingCallback();
        }
        super.onDestroy();
    }

    private void clearPendingCallback() {
        if (sCallback == mCallback) {
            sCallback = null;
        }
        mCallback = null;
    }
}
