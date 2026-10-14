package com.EdS.LeanKeyboardF.utils;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

/**
 * Thin, crash-safe wrapper around the system clipboard.
 *
 * Every method swallows the exceptions the clipboard can throw (no access
 * while another app has focus, binder errors, ...) and just reports "nothing
 * there" / "not written", because a clipboard problem must never take the
 * keyboard down.
 */
public final class SystemClipboard {
    private static final String LABEL = "LeanKeyboardF";

    // Same value as ClipDescription.EXTRA_IS_SENSITIVE (API 33). Password
    // managers set it on what they copy; such clips are never recorded.
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";

    private SystemClipboard() {
    }

    private static ClipboardManager manager(Context context) {
        Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
        return service instanceof ClipboardManager ? (ClipboardManager) service : null;
    }

    /**
     * Text of the current system clip, or null when there is none, it is not
     * text, it is marked sensitive, or the clipboard can't be read.
     */
    public static String readText(Context context) {
        try {
            ClipboardManager clipboard = manager(context);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return null;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || isSensitive(clip)) {
                return null;
            }

            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text == null ? null : text.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Puts plain text on the system clipboard. Returns false if that failed. */
    public static boolean writeText(Context context, CharSequence text) {
        try {
            ClipboardManager clipboard = manager(context);
            if (clipboard == null || text == null) {
                return false;
            }

            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Empties the system clipboard. */
    public static void clear(Context context) {
        try {
            ClipboardManager clipboard = manager(context);
            if (clipboard == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= 28) {
                clipboard.clearPrimaryClip();
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, ""));
            }
        } catch (RuntimeException e) {
            // nothing sensible to do
        }
    }

    /**
     * Adds the current system clip to the keyboard's history (when sync is
     * on). Blank text is ignored by the history itself, and an item that is
     * already there is just moved to the front, so calling this repeatedly
     * is harmless.
     */
    public static void importToHistory(Context context) {
        LeanKeyPreferences prefs = LeanKeyPreferences.instance(context);
        if (!prefs.isClipboardSyncEnabled()) {
            return;
        }

        String text = readText(context);
        if (text != null) {
            prefs.addClipboardHistoryItem(text);
        }
    }

    private static boolean isSensitive(ClipData clip) {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }

        ClipDescription description = clip.getDescription();
        PersistableBundle extras = description == null ? null : description.getExtras();
        return extras != null && extras.getBoolean(EXTRA_IS_SENSITIVE, false);
    }
}
