package com.EdS.LeanKeyboardF.ime;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.widget.FrameLayout;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import com.EdS.LeanKeyboardF.R;
import com.EdS.LeanKeyboardF.ime.LeanbackKeyboardController.InputListener;
import com.EdS.LeanKeyboardF.utils.JapaneseKana;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.utils.LeanbackDebugLog;
import com.EdS.LeanKeyboardF.utils.LegacyCompat;
import com.EdS.LeanKeyboardF.utils.LearningDictionary;
import com.EdS.LeanKeyboardF.utils.SystemClipboard;

import java.util.ArrayList;
import java.util.List;

public class LeanbackImeService extends KeyMapperImeService {
    private static final String TAG = LeanbackImeService.class.getSimpleName();
    private static final boolean DEBUG = false;
    public static final String IME_CLOSE = "com.google.android.athome.action.IME_CLOSE";
    public static final String IME_OPEN = "com.google.android.athome.action.IME_OPEN";
    public static final int MAX_SUGGESTIONS = 10;
    static final int MODE_FREE_MOVEMENT = 1;
    static final int MODE_TRACKPAD_NAVIGATION = 0;
    private static final int MSG_SUGGESTIONS_CLEAR = 123;
    private static final int SUGGESTIONS_CLEAR_DELAY = 1000;
    private boolean mEnterSpaceBeforeCommitting;
    private View mInputView;
    private LeanbackKeyboardController mKeyboardController;
    private boolean mShouldClearSuggestions = true;
    private LeanbackSuggestionsFactory mSuggestionsFactory;
    public static final String COMMAND_RESTART = "restart";
    private boolean mForceShowKbd;
    private boolean mLastFloatingState;
    private boolean mLastLegacyCompatState;
    private int mLastSizeLevel;

    // The keyboard's root view while the floating keyboard is on (it sits
    // inside a wrapper, see createInputView()); null otherwise.
    private View mFloatingRoot;

    // Keys swallowed while moving the floating keyboard: their key-up must
    // be swallowed too, otherwise the OK key-up that ends "move" mode would
    // press whatever button is focused (the Move button) and start it again.
    private final SparseBooleanArray mMoveKeysDown = new SparseBooleanArray();

    // Keeps the "Буфер" history in step with the system clipboard.
    private final ClipboardManager.OnPrimaryClipChangedListener mClipboardListener =
            () -> SystemClipboard.importToHistory(this);

    @SuppressLint("HandlerLeak")
    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SUGGESTIONS_CLEAR && mShouldClearSuggestions) {
                InputConnection connection = getCurrentInputConnection();
                if (connection != null) {
                    // Was: always wiped the row with the old (empty for
                    // normal fields) suggestion system, regardless of
                    // whether Learn Keyboard still had something
                    // relevant to show - so any suggestion vanished
                    // ~1s after typing no matter what. Recompute
                    // properly instead of blindly clearing.
                    refreshSuggestions(connection);
                } else {
                    mSuggestionsFactory.clearSuggestions();
                    mKeyboardController.updateSuggestions(mSuggestionsFactory.getSuggestions());
                }
                mShouldClearSuggestions = false;
            }

        }
    };

    private InputListener mInputListener = this::handleTextEntry;

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    public LeanbackImeService() {
        if (VERSION.SDK_INT < 21 && !enableHardwareAcceleration()) {
            Log.w("LbImeService", "Could not enable hardware acceleration");
        }
    }

    @Override
    public void onCreate() {
        //setupDensity();

        // Before super.onCreate(): that is where onInitializeInterface()
        // builds the keyboard view, i.e. where the compat layouts are
        // needed. Does nothing when "Legacy Android mode" is off.
        LegacyCompat.runStartupChecks(this);

        super.onCreate();

        LeanbackDebugLog.d(this, TAG, "onCreate");

        initSettings();

        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.addPrimaryClipChangedListener(mClipboardListener);
            }
        } catch (RuntimeException e) {
            LeanbackDebugLog.d(this, TAG, "clipboard listener not registered: " + e);
        }
    }

    @Override
    public void onDestroy() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.removePrimaryClipChangedListener(mClipboardListener);
            }
        } catch (RuntimeException e) {
            // service is going away anyway
        }

        super.onDestroy();
    }

    private void setupDensity() {
        float sizeFactor = LeanKeyPreferences.instance(this).getKeyboardSizeFactor();
        if (sizeFactor > 1.0f) {
            DisplayMetrics metrics = LeanbackUtils.createMetricsFrom(this, sizeFactor);

            if (metrics != null) {
                getResources().getDisplayMetrics().setTo(metrics);
            }
        }
    }

    private void initSettings() {
        LeanKeyPreferences prefs = LeanKeyPreferences.instance(this);
        mForceShowKbd = prefs.getForceShowKeyboard();

        if (mKeyboardController != null) {
            mKeyboardController.setSuggestionsEnabled(prefs.getSuggestionsEnabled());
            mKeyboardController.setHideWhenPhysicalKeyboardUsed(!mForceShowKbd);
        }
    }

    // Learn Keyboard suggestions must bypass the old echo-the-whole-field
    // behaviour (see updateSuggestionsRaw), while domain-mode (email
    // fields) keeps it, since that's existing, working behaviour.
    private void refreshSuggestions(InputConnection connection) {
        ArrayList<String> suggestions = computeSuggestions(connection);

        // updateSuggestions() (unlike ...Raw) fills slot 0 with the text
        // typed so far. That is what password fields and "Learn Keyboard
        // off" rely on: computeSuggestions() then returns the factory's
        // list, whose slot 0 is only an empty placeholder - sent through
        // the Raw path it showed up as a blank suggestion button.
        if (mSuggestionsFactory.shouldSuggestionsAmend() || !isLearnedSuggestionsActive()) {
            mKeyboardController.updateSuggestions(suggestions);
        } else {
            mKeyboardController.updateSuggestionsRaw(suggestions);
        }
    }

    // True when the suggestions row shows Learn Keyboard's predictions
    // (never in password fields, never when the feature is switched off).
    private boolean isLearnedSuggestionsActive() {
        return LeanKeyPreferences.instance(this).isLearnKeyboardEnabled()
                && !LeanbackUtils.isPasswordField(getCurrentInputEditorInfo());
    }

    // Called right after a word-boundary character (space, punctuation)
    // is committed - records the word that was just finished, and the
    // word-pair (bigram) with whatever came before it, for Learn
    // Keyboard's suggestions.
    private void learnFromCursor(InputConnection connection) {
        if (!LeanKeyPreferences.instance(this).isLearnKeyboardEnabled()) {
            return;
        }

        if (LeanbackUtils.isPasswordField(getCurrentInputEditorInfo())) {
            return;
        }

        String[] words = LeanbackUtils.getLastTwoWords(connection);
        String justCompleted = words[0];
        String before = words[1];

        if (!justCompleted.isEmpty()) {
            LearningDictionary dictionary = LearningDictionary.instance(this);
            dictionary.learnWord(justCompleted);

            if (!before.isEmpty()) {
                dictionary.learnBigram(before, justCompleted);
            }
        }
    }

    // For text that may contain multiple words at once (voice
    // recognition results) - learns each word individually plus the
    // bigrams between them, instead of storing the whole phrase as one
    // "word" (which learnWord() would otherwise do, since it doesn't
    // split on whitespace itself).
    private void learnPhrase(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return;
        }

        String[] tokens = phrase.trim().split("\\s+");
        LearningDictionary dictionary = LearningDictionary.instance(this);
        String previous = null;

        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }

            dictionary.learnWord(token);

            if (previous != null) {
                dictionary.learnBigram(previous, token);
            }

            previous = token;
        }
    }

    // Replaces the old suggestion source (which only ever did anything
    // for email domain fields, or if the target app happened to supply
    // its own completions) with Learn Keyboard's own ranked predictions,
    // while it's enabled and the field isn't in that domain-amend mode.
    private ArrayList<String> computeSuggestions(InputConnection connection) {
        if (mSuggestionsFactory.shouldSuggestionsAmend() || !isLearnedSuggestionsActive()) {
            return mSuggestionsFactory.getSuggestions();
        }

        boolean atBoundary = LeanbackUtils.isAtWordBoundary(connection);
        String[] words = LeanbackUtils.getLastTwoWords(connection);
        String relevantWord = words[0];

        if (relevantWord.isEmpty()) {
            // Nothing usable immediately before the cursor - e.g. it
            // just moved to the very start of the field, or in front of
            // a word entirely, via an arrow key. If there's a word right
            // after the cursor, treat it the same as a word being
            // typed/edited so the suggestion doesn't just vanish every
            // time the cursor moves near it.
            relevantWord = LeanbackUtils.getWordAfterCursor(connection);
            atBoundary = false;
        }

        LearningDictionary dictionary = LearningDictionary.instance(this);
        ArrayList<String> result = new ArrayList<>();

        if (!relevantWord.isEmpty()) {
            List<String> learned;
            boolean capitalize;

            if (atBoundary) {
                learned = dictionary.getNextWordSuggestions(relevantWord, MAX_SUGGESTIONS);
                // Words are stored lowercase (see LearningDictionary) -
                // capitalize the suggestion if this word starts a new
                // sentence, matching normal capitalization expectations.
                capitalize = LeanbackUtils.isSentenceStart(connection);
            } else {
                learned = dictionary.getPrefixSuggestions(relevantWord, MAX_SUGGESTIONS);
                // Match whatever casing the user has typed so far -
                // typing "H" should suggest "Hello", not "hello".
                capitalize = Character.isUpperCase(relevantWord.charAt(0));

                if (learned.isEmpty()) {
                    // The cursor can land exactly at the end of a
                    // complete, already-known word without a boundary
                    // character after it yet - most commonly by pressing
                    // an arrow key right back onto the end of the word
                    // that just triggered a "next word" suggestion. A
                    // prefix search then only matches words that extend
                    // this one (excluding the word itself), which is
                    // usually nothing, so the suggestion would vanish.
                    // Falling back to "what usually follows this word"
                    // keeps it showing instead.
                    learned = dictionary.getNextWordSuggestions(relevantWord, MAX_SUGGESTIONS);
                    capitalize = LeanbackUtils.isSentenceStart(connection);
                }
            }

            for (String word : learned) {
                result.add(capitalize ? capitalizeFirst(word) : word);
            }
        }

        return result;
    }

    private String capitalizeFirst(String word) {
        if (word.isEmpty()) {
            return word;
        }

        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private void clearSuggestionsDelayed() {
        if (!mSuggestionsFactory.shouldSuggestionsAmend()) {
            mHandler.removeMessages(MSG_SUGGESTIONS_CLEAR);
            mShouldClearSuggestions = true;
            mHandler.sendEmptyMessageDelayed(MSG_SUGGESTIONS_CLEAR, SUGGESTIONS_CLEAR_DELAY);
        }

    }

    // Called right before performContextMenuAction(copy/cut) actually
    // runs, while the selection still exists, so the copied/cut text can
    // be added to the "Буфер" history (not just the single system
    // clipboard slot the OS itself keeps).
    // "゛゜" key of the Japanese layouts: replaces the character before the
    // cursor with its next form (か -> が, は -> ば -> ぱ, つ -> っ -> づ ...).
    // Does nothing when text is selected, when there is no character before
    // the cursor, or when that character has no other form.
    private void transformPreviousKana(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            return;
        }

        CharSequence before = connection.getTextBeforeCursor(1, 0);
        if (before == null || before.length() != 1) {
            return;
        }

        Character next = JapaneseKana.next(before.charAt(0));
        if (next == null) {
            return;
        }

        connection.beginBatchEdit();
        connection.deleteSurroundingText(1, 0);
        connection.commitText(String.valueOf(next), 1);
        connection.endBatchEdit();
    }

    private void captureSelectionToClipboardHistory(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);

        if (selected != null && selected.length() > 0) {
            LeanKeyPreferences.instance(this).addClipboardHistoryItem(selected.toString());
        }
    }

    // Copy / Cut. The app is asked to do the real copy/cut first (it knows
    // best, e.g. rich text). Some apps ignore these context-menu actions;
    // then the text is put on the system clipboard here, and for Cut the
    // selection is removed, so the buttons still behave like the real
    // thing instead of silently doing nothing.
    private void copyOrCutSelection(InputConnection connection, boolean cut) {
        CharSequence selected = connection.getSelectedText(0);
        String text = selected == null ? null : selected.toString();

        captureSelectionToClipboardHistory(connection);

        boolean handled = connection.performContextMenuAction(cut ? android.R.id.cut : android.R.id.copy);

        if (!handled && text != null && !text.isEmpty()) {
            SystemClipboard.writeText(this, text);

            if (cut) {
                connection.commitText("", 1);
            }
        }
    }

    // Paste. Same idea: let the app paste; if it does not support the
    // action, insert the system clipboard text directly.
    private void pasteFromClipboard(InputConnection connection) {
        boolean handled = connection.performContextMenuAction(android.R.id.paste);

        if (!handled) {
            String text = SystemClipboard.readText(this);

            if (text != null && !text.isEmpty()) {
                connection.commitText(text, 1);
            }
        }
    }

    private void handleTextEntry(final int type, final int keyCode, final CharSequence text) {
        final InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            boolean updateSuggestions;
            switch (type) {
                case InputListener.ENTRY_TYPE_STRING:
                    clearSuggestionsDelayed();

                    // Japanese layouts: the "゛゜" key changes the previous
                    // kana instead of typing something.
                    if (keyCode == JapaneseKana.KEYCODE_TRANSFORM) {
                        transformPreviousKana(connection);
                        mEnterSpaceBeforeCommitting = false;
                        updateSuggestions = false;
                        break;
                    }

                    if (mEnterSpaceBeforeCommitting && mKeyboardController.enableAutoEnterSpace()) {
                        // No space before kana: Japanese is written without spaces.
                        if (LeanbackUtils.isAlphabet(keyCode) && !JapaneseKana.isKana(keyCode)) {
                            connection.commitText(" ", 1);
                        }

                        mEnterSpaceBeforeCommitting = false;
                    }

                    connection.commitText(text, 1);
                    updateSuggestions = true;
                    if (keyCode == LeanbackKeyboardView.ASCII_PERIOD) {
                        mEnterSpaceBeforeCommitting = true;
                    }

                    if (keyCode > 0 && LeanbackUtils.isWordBoundary((char) keyCode)) {
                        learnFromCursor(connection);
                    }
                    break;
                case InputListener.ENTRY_TYPE_BACKSPACE:
                    clearSuggestionsDelayed();
                    CharSequence backspaceSelected = connection.getSelectedText(0);
                    if (backspaceSelected != null && backspaceSelected.length() > 0) {
                        connection.commitText("", 1);
                    } else {
                        connection.deleteSurroundingText(1, 0);
                    }
                    mEnterSpaceBeforeCommitting = false;
                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_SUGGESTION:
                case InputListener.ENTRY_TYPE_VOICE:
                    clearSuggestionsDelayed();
                    if (!mSuggestionsFactory.shouldSuggestionsAmend()) {
                        // Only delete the current word being replaced -
                        // getCharLengthBeforeCursor/AfterCursor return up
                        // to 1000 characters around the cursor (i.e.
                        // basically the whole field), which would wipe
                        // out everything else typed, not just the word
                        // this suggestion replaces.
                        int[] wordBounds = LeanbackUtils.getCurrentWordBoundaryLengths(connection);
                        connection.deleteSurroundingText(wordBounds[0], wordBounds[1]);
                    } else {
                        int location = LeanbackUtils.getAmpersandLocation(connection);
                        connection.setSelection(location, location);
                        connection.deleteSurroundingText(0, LeanbackUtils.getCharLengthAfterCursor(connection));
                    }

                    connection.commitText(text, 1);
                    mEnterSpaceBeforeCommitting = true;

                    if (LeanKeyPreferences.instance(this).isLearnKeyboardEnabled()
                            && !LeanbackUtils.isPasswordField(getCurrentInputEditorInfo())) {
                        learnPhrase(text.toString());
                    }

                    // Picking a suggestion or a voice result should not
                    // also submit the field (that's what the fall-through
                    // into ENTRY_TYPE_ACTION below used to do).
                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_ACTION:  // User presses Go, Send, Search etc
                    // No boundary character (space/punctuation) gets
                    // typed before this - without this, the last word
                    // the user typed would never get learned. Only do it
                    // if there's actually a fresh, un-learned word at the
                    // cursor (mid-word) - if the user already typed a
                    // trailing space/punctuation, that already learned
                    // it, and doing it again here would double-count it.
                    if (!LeanbackUtils.isAtWordBoundary(connection)) {
                        learnFromCursor(connection);
                    }

                    boolean result = sendDefaultEditorAction(true);

                    if (result) {
                        hideWindow(); // SmartYouTubeTV: hide kbd on search page fix
                    } else {
                        LeanbackUtils.sendEnterKey(connection);
                    }

                    updateSuggestions = false;
                    break;
                case InputListener.ENTRY_TYPE_BUFFER_SUGGESTION:
                    // Picking an item from the "Буфер" history list -
                    // unlike a normal word suggestion, this must NOT
                    // delete/replace whatever is around the cursor, it
                    // just inserts the picked text at the current
                    // position (like a real paste).
                    clearSuggestionsDelayed();
                    connection.commitText(text, 1);
                    // Make the picked item the current system clipboard
                    // too, so a later paste (here or in another app)
                    // uses it.
                    if (text != null && LeanKeyPreferences.instance(this).isClipboardSyncEnabled()) {
                        SystemClipboard.writeText(this, text);
                    }
                    mEnterSpaceBeforeCommitting = true;
                    updateSuggestions = false;
                    break;
                case InputListener.ENTRY_TYPE_CLIPBOARD:
                    // keyCode is which button was pressed: 0=Clear,
                    // 1=Select All, 2=Copy, 3=Cut, 4=Paste - matches the
                    // button order in input_leanback.xml's
                    // action_buttons column.
                    switch (keyCode) {
                        case 0: // Clear - wipes the whole field, not just around the cursor
                            // Integer.MAX_VALUE crashes some apps' InputConnection
                            // implementations (internal length math overflows) -
                            // especially reliably when there's an active selection
                            // already (e.g. right after Select All). Replace any
                            // selection first, then delete the rest with a large
                            // but bounded number instead of MAX_VALUE.
                            CharSequence selectedText = connection.getSelectedText(0);
                            if (selectedText != null && selectedText.length() > 0) {
                                connection.commitText("", 1);
                            }
                            connection.deleteSurroundingText(9999, 9999);
                            mEnterSpaceBeforeCommitting = false;
                            break;
                        case 1: // Select All
                            connection.performContextMenuAction(android.R.id.selectAll);
                            break;
                        case 2: // Copy
                            copyOrCutSelection(connection, false);
                            break;
                        case 3: // Cut
                            copyOrCutSelection(connection, true);
                            mEnterSpaceBeforeCommitting = false;
                            break;
                        case 4: // Paste
                            pasteFromClipboard(connection);
                            mEnterSpaceBeforeCommitting = false;
                            break;
                        case 5: // Move (floating keyboard only)
                            enterMoveMode();
                            break;
                        default:
                            break;
                    }

                    clearSuggestionsDelayed();
                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_LEFT:
                case InputListener.ENTRY_TYPE_RIGHT:
                    // getTextBeforeCursor(1000, ...)'s returned length is
                    // capped at 1000 by the Android API itself, no matter
                    // how far the real cursor position is - past that
                    // point every arrow press recomputed roughly the same
                    // "around 1000" index (yuliskov/LeanKeyboard#51).
                    // ExtractedText.selectionStart/End give the real
                    // absolute position with no such cap.
                    ExtractedText extractedText = connection.getExtractedText(new ExtractedTextRequest(), 0);

                    if (extractedText != null) {
                        int selStart = extractedText.selectionStart;
                        int selEnd = extractedText.selectionEnd;
                        int textLength = extractedText.text != null ? extractedText.text.length() : selEnd;

                        int newIndex;
                        if (type == InputListener.ENTRY_TYPE_LEFT) {
                            int from = Math.min(selStart, selEnd);
                            newIndex = Math.max(0, from - 1);
                        } else {
                            int from = Math.max(selStart, selEnd);
                            newIndex = Math.min(textLength, from + 1);
                        }

                        LeanbackDebugLog.d(this, TAG, "direction key: index: " + newIndex);

                        connection.setSelection(newIndex, newIndex);
                    }

                    updateSuggestions = true;
                    break;
                case InputListener.ENTRY_TYPE_DISMISS:
                    connection.performEditorAction(EditorInfo.IME_ACTION_NONE);
                    updateSuggestions = false;
                    break;
                case InputListener.ENTRY_TYPE_VOICE_DISMISS:
                    connection.performEditorAction(EditorInfo.IME_ACTION_GO);
                    updateSuggestions = false;
                    break;
                default:
                    updateSuggestions = true;
            }

            if (mKeyboardController.areSuggestionsEnabled() && updateSuggestions) {
                refreshSuggestions(connection);
            }
        }
    }

    @Override
    public View onCreateInputView() {
        mInputView = createInputView();
        mInputView.requestFocus();

        return mInputView;
    }

    // The view handed to the system. InputMethodService#setInputView() adds
    // it to its frame with its own params (MATCH_PARENT x WRAP_CONTENT) and
    // so throws away whatever size/gravity/margin the keyboard's root view
    // asked for. For the floating panel the root view is therefore put into
    // a small FrameLayout of ours: the params given to the root *inside* it
    // are respected, which is what gives the panel its gap above the screen
    // edge. The normal full-width keyboard is returned as it is.
    private View createInputView() {
        View root = mKeyboardController.getView();

        if (!LeanKeyPreferences.instance(this).isFloatingKeyboard()) {
            mFloatingRoot = null;
            return root;
        }

        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.floating_keyboard_bottom_margin);

        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setClipChildren(false);
        wrapper.addView(root, params);

        mFloatingRoot = root;
        applyFloatingPosition();

        return wrapper;
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] infos) {
        if (mKeyboardController.areSuggestionsEnabled()) {
            mShouldClearSuggestions = false;
            mHandler.removeMessages(123);
            mSuggestionsFactory.onDisplayCompletions(infos);
            mKeyboardController.updateSuggestions(this.mSuggestionsFactory.getSuggestions());
        }

    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // don't change it (true shows edit dialog above kbd)
    }

    /**
     * At this point, decision whether to show kbd taking place<br/>
     * <a href="https://stackoverflow.com/questions/7449283/is-it-possible-to-have-both-physical-keyboard-and-soft-keyboard-active-at-the-sa">More info</a>
     * @return whether to show kbd
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        LeanbackDebugLog.d(this, TAG, "onEvaluateInputViewShown");
        return mForceShowKbd || super.onEvaluateInputViewShown();
    }

    // FireTV fix
    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        LeanbackDebugLog.d(this, TAG, "onShowInputRequested");
        return mForceShowKbd || super.onShowInputRequested(flags, configChange);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        sendBroadcast(new Intent(IME_CLOSE));
        mSuggestionsFactory.clearSuggestions();

        // NOTE: Trying to fix kbd without UI bug (telegram)
        reInitKeyboard();
    }

    @SuppressLint("NewApi")
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return isInputViewShown() &&
                (event.getSource() & InputDevice.SOURCE_TOUCH_NAVIGATION) == InputDevice.SOURCE_TOUCH_NAVIGATION &&
                mKeyboardController.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @SuppressLint("WrongConstant")
    public void hideIme() {
        requestHideSelf(InputMethodService.BACK_DISPOSITION_DEFAULT);
    }

    @Override
    public void onInitializeInterface() {
        mKeyboardController = new LeanbackKeyboardController(this, mInputListener);
        mEnterSpaceBeforeCommitting = false;
        mSuggestionsFactory = new LeanbackSuggestionsFactory(this, MAX_SUGGESTIONS);
        mLastFloatingState = LeanKeyPreferences.instance(this).isFloatingKeyboard();
        mLastLegacyCompatState = LegacyCompat.isEnabled(this);
        mLastSizeLevel = LeanKeyPreferences.instance(this).getKeyboardSizeLevel();

        // Runs here, not only from onCreate(): the system calls this method
        // from inside super.onCreate() - i.e. BEFORE onCreate() got to read
        // the settings - so "Keep on screen" was always seen as off and
        // the keyboard hid itself on the first physical key press. It
        // also re-applies the "suggestions" setting to a controller that
        // was just rebuilt (rotation, floating/compat toggled).
        initSettings();
    }

    @Override
    public void onConfigureWindow(Window win, boolean isFullscreen, boolean isCandidatesOnly) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly);

        // The window is the standard one - full width, at the bottom -
        // in BOTH modes. The floating panel is positioned *inside* it (see
        // createInputView() and applyFloatingPosition()). Making the window
        // itself narrow (WRAP_CONTENT) and moving it with x/y used to be
        // tried; that depends on how the system sizes wrap-content IME
        // windows and on it honouring x/y for them, which differs between
        // Android TV firmwares, so it is not relied on any more.
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        win.setGravity(android.view.Gravity.BOTTOM);
    }

    private Window getImeWindow() {
        return getWindow() != null ? getWindow().getWindow() : null;
    }

    // Where the floating panel is: it sits at the bottom centre of the
    // window, shifted by the offsets the user set with the Move button
    // (X > 0 = right of centre, Y > 0 = raised). Done with margins of the
    // panel inside our wrapper, which is plain, reliable layout; the IME
    // window just grows to include the raised part.
    private void applyFloatingPosition() {
        View panel = mFloatingRoot;
        if (panel == null || !(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }

        LeanKeyPreferences prefs = LeanKeyPreferences.instance(this);
        int base = getResources().getDimensionPixelSize(R.dimen.floating_keyboard_bottom_margin);
        int x = prefs.getFloatingOffsetX();
        int y = prefs.getFloatingOffsetY();

        // Keep the panel fully on screen, whatever was stored (a bigger
        // panel or another screen size would otherwise leave part of it
        // outside). Skipped until the panel has been laid out once.
        int width = panel.getWidth();
        int height = panel.getHeight();
        if (width > 0 && height > 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int maxX = Math.max(0, (metrics.widthPixels - width) / 2);
            int maxY = Math.max(0, metrics.heightPixels - height - base);
            x = Math.max(-maxX, Math.min(maxX, x));
            y = Math.max(0, Math.min(maxY, y));
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int left = Math.max(x, 0);
        int right = Math.max(-x, 0);
        int bottom = base + y;
        if (params.leftMargin != left || params.rightMargin != right || params.bottomMargin != bottom) {
            params.leftMargin = left;
            params.rightMargin = right;
            params.bottomMargin = bottom;
            panel.setLayoutParams(params);
        }
    }

    // Floating panel: only the panel itself takes touches - the rest of
    // the (full-width) window lets them through to the app. A shifted or
    // raised panel also hovers over the app, so the app must not be
    // squeezed into the strip above it: report "no inset" (the same thing
    // full-screen mode does). At the default position the app is resized
    // exactly as with any other keyboard.
    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);

        View panel = mFloatingRoot;
        if (panel == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) {
            return;
        }

        int[] location = new int[2];
        panel.getLocationInWindow(location);
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
        outInsets.touchableRegion.set(location[0], location[1],
                location[0] + panel.getWidth(), location[1] + panel.getHeight());

        Window win = getImeWindow();
        if (win != null && LeanKeyPreferences.instance(this).isFloatingMoved()) {
            int height = win.getDecorView().getHeight();
            outInsets.contentTopInsets = height;
            outInsets.visibleTopInsets = height;
        }
    }

    // "Move" button pressed: arrow keys now move the keyboard.
    private void enterMoveMode() {
        if (!LeanKeyPreferences.instance(this).isFloatingKeyboard()) {
            return;
        }

        mKeyboardController.setMoveMode(true);
        android.widget.Toast.makeText(this, R.string.move_keyboard_hint, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void exitMoveMode() {
        mKeyboardController.setMoveMode(false);
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();

        // Never come back to a keyboard that is still stuck in "move" mode.
        if (mKeyboardController != null) {
            exitMoveMode();
        }
        mMoveKeysDown.clear();
    }

    // Returns true if the key was consumed by "move" mode.
    private boolean handleMoveModeKeyDown(int keyCode, KeyEvent event) {
        int dx = 0;
        int raise = 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dx = -1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dx = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                raise = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                raise = -1;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // OK or Back = done. Only on the first press, so holding
                // the OK that started the mode doesn't end it at once.
                if (event.getRepeatCount() == 0) {
                    exitMoveMode();
                }
                mMoveKeysDown.put(keyCode, true);
                return true;
            default:
                return false;
        }

        mMoveKeysDown.put(keyCode, true);
        moveFloatingKeyboard(dx, raise, event.getRepeatCount());
        return true;
    }

    // Moves the floating keyboard one step (bigger steps while a key is
    // held) and remembers the new position. The position is kept inside
    // the screen.
    private void moveFloatingKeyboard(int dx, int raise, int repeatCount) {
        View panel = mFloatingRoot;
        if (panel == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) {
            return; // not floating, or not laid out yet
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int step = Math.round(metrics.density * (repeatCount >= 6 ? 48 : 16));
        int base = getResources().getDimensionPixelSize(R.dimen.floating_keyboard_bottom_margin);

        LeanKeyPreferences prefs = LeanKeyPreferences.instance(this);
        int maxX = Math.max(0, (metrics.widthPixels - panel.getWidth()) / 2);
        int maxY = Math.max(0, metrics.heightPixels - panel.getHeight() - base);
        int x = Math.max(-maxX, Math.min(maxX, prefs.getFloatingOffsetX() + dx * step));
        int y = Math.max(0, Math.min(maxY, prefs.getFloatingOffsetY() + raise * step));

        prefs.setFloatingOffset(x, y);
        applyFloatingPosition();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // "Move" mode of the floating keyboard takes the arrow keys (and
        // OK/Back to finish) before anything else sees them.
        if (isInputViewShown() && mKeyboardController.isMoveMode() && handleMoveModeKeyDown(keyCode, event)) {
            return true;
        }

        //// DOESN'T WORK!!!
        //// Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        //event = mapEscToBack(event);
        //keyCode = mapEscToBack(keyCode);

        // Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hideIme();
            return true;
        }

        // A real hardware Enter/Numpad-Enter key must behave like a normal
        // key press (insert newline / trigger the field's IME action), not
        // "commit whatever on-screen key currently has focus" - which is
        // what this controller normally does for Enter, since it doubles
        // as the remote's D-pad-center/OK button. Bypass the controller
        // entirely for this key so the system handles it the standard way.
        if (mKeyboardController.isPhysicalPassthroughKey(keyCode, event)) {
            return super.onKeyDown(keyCode, event);
        }

        // Must run unconditionally (not gated by isInputViewShown() below) -
        // physical typing hides the on-screen keyboard, and the Alt+Shift
        // language-switch combo still needs to keep working after that.
        if (mKeyboardController.onPhysicalKeyDown(keyCode, event)) {
            return true;
        }

        return isInputViewShown() && mKeyboardController.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Swallow the key-up of every key "move" mode consumed on key-down.
        if (mMoveKeysDown.get(keyCode)) {
            mMoveKeysDown.delete(keyCode);
            return true;
        }

        //// DOESN'T WORK!!!
        //// Hide keyboard on ESC key: https://github.com/yuliskov/SmartYouTubeTV/issues/142
        //event = mapEscToBack(event);
        //keyCode = mapEscToBack(keyCode);

        // See the matching comment in onKeyDown() above.
        if (mKeyboardController.isPhysicalPassthroughKey(keyCode, event)) {
            return super.onKeyUp(keyCode, event);
        }

        if (mKeyboardController.onPhysicalKeyUp(keyCode, event)) {
            return true;
        }

        return isInputViewShown() && mKeyboardController.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    private KeyEvent mapEscToBack(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            // pay attention, you must pass the same action
            event = new KeyEvent(event.getAction(), KeyEvent.KEYCODE_BACK);
        }
        return event;
    }

    private int mapEscToBack(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            keyCode = KeyEvent.KEYCODE_BACK;
        }
        return keyCode;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null) {
            LeanbackDebugLog.d(this, TAG, "onStartCommand: " + intent.toUri(0));

            if (intent.getBooleanExtra(COMMAND_RESTART, false)) {
                LeanbackDebugLog.d(this, TAG, "onStartCommand: trying to restart service");

                reInitKeyboard();

                return Service.START_REDELIVER_INTENT;
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        mEnterSpaceBeforeCommitting = false;
        mSuggestionsFactory.onStartInput(info);
        mKeyboardController.onStartInput(info);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        // "Floating keyboard" (Misc settings) is only read once, when the
        // controller/root view are built in onInitializeInterface() -
        // which Android normally calls again only on a config change
        // (e.g. screen rotation), not every time the keyboard is shown.
        // Toggling the setting while this service's process is still
        // alive would otherwise have no visible effect until the app is
        // restarted. Check on every show and rebuild if it changed.
        //
        // "Legacy Android mode" (Misc -> Advanced) picks a different
        // set of layouts, so it needs the same rebuild.
        // Also pick up a position reset done in Settings meanwhile.
        applyFloatingPosition();

        boolean currentFloatingState = LeanKeyPreferences.instance(this).isFloatingKeyboard();
        boolean currentLegacyCompatState = LegacyCompat.isEnabled(this);
        // The keyboard size (Interface -> Size) is baked into the views when
        // they are built, so a changed level needs the same rebuild.
        int currentSizeLevel = LeanKeyPreferences.instance(this).getKeyboardSizeLevel();
        if (currentFloatingState != mLastFloatingState
                || currentLegacyCompatState != mLastLegacyCompatState
                || currentSizeLevel != mLastSizeLevel) {
            // A different panel size or mode: the old position no longer
            // fits it, so start again from the default spot.
            if (currentFloatingState != mLastFloatingState || currentSizeLevel != mLastSizeLevel) {
                LeanKeyPreferences.instance(this).resetFloatingOffset();
            }

            onInitializeInterface();
            mInputView = createInputView();
            setInputView(mInputView);
        }

        mKeyboardController.onStartInputView();
        sendBroadcast(new Intent(IME_OPEN));
        if (mKeyboardController.areSuggestionsEnabled()) {
            mSuggestionsFactory.createSuggestions();
            InputConnection startConnection = getCurrentInputConnection();
            if (startConnection != null) {
                refreshSuggestions(startConnection);
            } else {
                mKeyboardController.updateSuggestions(mSuggestionsFactory.getSuggestions());
            }

            // NOTE: FileManager+ rename item fix: https://t.me/LeanKeyboard/931
            // NOTE: Code below deletes text that has selection.
            //InputConnection connection = getCurrentInputConnection();
            //if (connection != null) {
            //    String text = LeanbackUtils.getEditorText(connection);
            //    connection.deleteSurroundingText(LeanbackUtils.getCharLengthBeforeCursor(connection), LeanbackUtils.getCharLengthAfterCursor(connection));
            //    connection.commitText(text, 1);
            //}
        }
    }

    private void reInitKeyboard() {
        initSettings();

        if (mKeyboardController != null) {
            mKeyboardController.initKeyboards();
        }
    }
}
