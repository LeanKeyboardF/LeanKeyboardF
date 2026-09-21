package com.EdS.LeanKeyboardF.ime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import androidx.core.text.BidiFormatter;
import com.EdS.LeanKeyboardF.ime.LeanbackKeyboardContainer.KeyFocus;
import com.EdS.LeanKeyboardF.utils.LeanbackDebugLog;
import com.EdS.LeanKeyboardF.R;

public class LeanbackUtils {
    private static final int ACCESSIBILITY_DELAY_MS = 250;
    private static final String EDITOR_LABEL = "label";
    private static final Handler sAccessibilityHandler = new Handler();
    private static final String TAG = LeanbackUtils.class.getSimpleName();

    public static int getImeAction(EditorInfo info) {
        return info.imeOptions & (EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_MASK_ACTION);
    }

    /**
     * Get class of the input
     * @param info attrs
     * @return constant e.g. {@link InputType#TYPE_CLASS_TEXT InputType.TYPE_CLASS_TEXT}
     */
    public static int getInputTypeClass(EditorInfo info) {
        return info.inputType & InputType.TYPE_MASK_CLASS;
    }

    /**
     * Get variation of the input
     * @param info attrs
     * @return constant e.g. {@link InputType#TYPE_DATETIME_VARIATION_DATE InputType.TYPE_DATETIME_VARIATION_DATE}
     */
    public static int getInputTypeVariation(EditorInfo info) {
        return info.inputType & InputType.TYPE_MASK_VARIATION;
    }

    // Never let Learn Keyboard learn or suggest from password fields.
    public static boolean isPasswordField(EditorInfo info) {
        if (info == null) {
            return false;
        }

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = getInputTypeVariation(info);

        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }

        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }

        return false;
    }

    public static boolean isAlphabet(int letter) {
        return Character.isLetter(letter);
    }

    @SuppressLint("NewApi")
    public static void sendAccessibilityEvent(final View view, boolean focusGained) {
        if (view != null && focusGained) {
            sAccessibilityHandler.removeCallbacksAndMessages(null);
            sAccessibilityHandler.postDelayed(() -> view.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT), ACCESSIBILITY_DELAY_MS);
        }

    }

    public static int getAmpersandLocation(InputConnection connection) {
        String text = getEditorText(connection);
        int pos = text.indexOf(64);
        if (pos < 0) { // not found
            pos = text.length();
        }

        return pos;
    }

    public static int getCharLengthAfterCursor(InputConnection connection) {
        int len = 0;
        CharSequence after = connection.getTextAfterCursor(1000, 0);
        if (after != null) {
            len = after.length();
        }

        return len;
    }

    public static int getCharLengthBeforeCursor(InputConnection connection) {
        int len = 0;
        CharSequence before = connection.getTextBeforeCursor(1000, 0);
        if (before != null) {
            len = before.length();
        }

        return len;
    }

    public static String getEditorText(InputConnection connection) {
        StringBuilder result = new StringBuilder();
        CharSequence before = connection.getTextBeforeCursor(1000, 0);
        CharSequence after = connection.getTextAfterCursor(1000, 0);
        if (before != null) {
            result.append(before);
        }

        if (after != null) {
            result.append(after);
        }

        return result.toString();
    }

    // Characters that end a "word" for the learning dictionary
    // (Learn Keyboard). Kept as its own small window (200 chars) around
    // the cursor rather than the whole field, both for speed and to
    // avoid the same kind of length-cap bug that affected arrow-key
    // navigation on long text (yuliskov/LeanKeyboard#51) - a "current
    // word" is never realistically anywhere near that long.
    private static final String WORD_BOUNDARY_CHARS = " \t\n.,!?;:()\"'\u2014\u2013";
    private static final int WORD_LOOKUP_WINDOW = 200;

    public static boolean isWordBoundary(char c) {
        return WORD_BOUNDARY_CHARS.indexOf(c) >= 0;
    }

    // [0]: the word right at the cursor - the word being typed if the
    //      cursor is mid-word, or the word just finished if the cursor is
    //      right after a boundary character (space, punctuation...).
    // [1]: the word before that one.
    // Either can be "" if not available (start of field, etc).
    public static String[] getLastTwoWords(InputConnection connection) {
        CharSequence before = connection.getTextBeforeCursor(WORD_LOOKUP_WINDOW, 0);
        if (before == null) {
            return new String[]{"", ""};
        }

        int end = before.length();
        while (end > 0 && isWordBoundary(before.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && !isWordBoundary(before.charAt(start - 1))) {
            start--;
        }
        String word1 = before.subSequence(start, end).toString();

        int end2 = start;
        while (end2 > 0 && isWordBoundary(before.charAt(end2 - 1))) {
            end2--;
        }
        int start2 = end2;
        while (start2 > 0 && !isWordBoundary(before.charAt(start2 - 1))) {
            start2--;
        }
        String word2 = before.subSequence(start2, end2).toString();

        return new String[]{word1, word2};
    }

    // How many characters of the *current word* sit before/after the
    // cursor (e.g. [3, 2] if the cursor is in the middle of a 5-letter
    // word). Used when replacing a word with a picked suggestion, so
    // only that word is deleted - not the whole field.
    public static int[] getCurrentWordBoundaryLengths(InputConnection connection) {
        int beforeLen = 0;
        CharSequence before = connection.getTextBeforeCursor(WORD_LOOKUP_WINDOW, 0);
        if (before != null) {
            int i = before.length();
            while (i > 0 && !isWordBoundary(before.charAt(i - 1))) {
                i--;
            }
            beforeLen = before.length() - i;
        }

        int afterLen = 0;
        CharSequence after = connection.getTextAfterCursor(WORD_LOOKUP_WINDOW, 0);
        if (after != null) {
            int i = 0;
            while (i < after.length() && !isWordBoundary(after.charAt(i))) {
                i++;
            }
            afterLen = i;
        }

        return new int[]{beforeLen, afterLen};
    }

    // True if the cursor is at the very start of the field, or right
    // after a sentence-ending character (., !, ?) plus optional
    // whitespace - used to decide whether the next suggested word should
    // be capitalized.
    public static boolean isSentenceStart(InputConnection connection) {
        CharSequence before = connection.getTextBeforeCursor(10, 0);
        if (before == null || before.length() == 0) {
            return true;
        }

        for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i);
            if (c == ' ' || c == '\t') {
                continue;
            }
            return c == '.' || c == '!' || c == '?' || c == '\n';
        }

        return true;
    }

    // True if the character immediately before the cursor is itself a
    // word-boundary character (or there's nothing before the cursor) -
    // i.e. the user isn't mid-word right now.
    public static boolean isAtWordBoundary(InputConnection connection) {
        CharSequence lastChar = connection.getTextBeforeCursor(1, 0);
        return lastChar == null || lastChar.length() == 0 || isWordBoundary(lastChar.charAt(0));
    }

    // The word starting right at the cursor, looking forward. Used as a
    // fallback when there's nothing usable *before* the cursor (e.g. the
    // cursor just moved to the very start of the field, or landed right
    // in front of a word via an arrow key) - without this, a suggestion
    // could vanish just from moving the cursor there, even though the
    // word it was based on is still right next to it.
    public static String getWordAfterCursor(InputConnection connection) {
        CharSequence after = connection.getTextAfterCursor(WORD_LOOKUP_WINDOW, 0);
        if (after == null) {
            return "";
        }

        int i = 0;
        while (i < after.length() && !isWordBoundary(after.charAt(i))) {
            i++;
        }

        return after.subSequence(0, i).toString();
    }

    public static void sendEnterKey(InputConnection connection) {
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }

    public static String getEditorLabel(EditorInfo info) {
        if (info != null && info.extras != null && info.extras.containsKey(EDITOR_LABEL)) {
            return info.extras.getString(EDITOR_LABEL);
        }

        return null;
    }

    public static DisplayMetrics createMetricsFrom(Context context, float factor) {
        DisplayMetrics metrics = null;
        Object service = context.getSystemService(Context.WINDOW_SERVICE);

        if (service instanceof WindowManager) {
            WindowManager manager = (WindowManager) service;
            metrics = new DisplayMetrics();
            manager.getDefaultDisplay().getMetrics(metrics);
            LeanbackDebugLog.d(context, TAG, metrics.toString());

            // new values
            metrics.density *= factor;
            metrics.densityDpi *= factor;
            metrics.heightPixels *= factor;
            metrics.widthPixels *= factor;
            metrics.scaledDensity *= factor;
            metrics.xdpi *= factor;
            metrics.ydpi *= factor;
        }

        return metrics;
    }

    public static void showKeyboardPicker(Context context) {
        if (context != null) {
            InputMethodManager imeManager = (InputMethodManager) context.getApplicationContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imeManager != null) {
                imeManager.showInputMethodPicker();
            }
        }
    }

    public static int getRtlLenAfterCursor(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }

        BidiFormatter formatter = BidiFormatter.getInstance();
        int len = 0;

        for (int i = 1; i < text.length(); i++) {
            CharSequence charSequence = text.subSequence(len, i);
            if (formatter.isRtl(charSequence)) {
                len++;
            } else {
                break;
            }
        }

        return len;
    }

    public static int getRtlLenBeforeCursor(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }

        BidiFormatter formatter = BidiFormatter.getInstance();
        int len = 0;

        for (int i = text.length(); i > 0; i--) {
            CharSequence charSequence = text.subSequence(i-1, i);
            if (formatter.isRtl(charSequence)) {
                len++;
            } else {
                break;
            }
        }

        return len;
    }

    public static boolean isSubmitButton(KeyFocus focus) {
        return focus.index == 0 && focus.type == KeyFocus.TYPE_ACTION;
    }

    public static boolean isSuggestionsButton(KeyFocus focus) {
        return focus.type == KeyFocus.TYPE_SUGGESTION;
    }
}
