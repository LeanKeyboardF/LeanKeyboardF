package com.EdS.LeanKeyboardF.ime;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import com.EdS.LeanKeyboardF.ime.LeanbackKeyboardController.InputListener;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.utils.LeanbackDebugLog;
import com.EdS.LeanKeyboardF.utils.LegacyCompat;
import com.EdS.LeanKeyboardF.utils.LearningDictionary;

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
    }

    private void setupDensity() {
        if (LeanKeyPreferences.instance(this).getEnlargeKeyboard()) {
            DisplayMetrics metrics = LeanbackUtils.createMetricsFrom(this, 1.3f);

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
    private void captureSelectionToClipboardHistory(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);

        if (selected != null && selected.length() > 0) {
            LeanKeyPreferences.instance(this).addClipboardHistoryItem(selected.toString());
        }
    }

    private void handleTextEntry(final int type, final int keyCode, final CharSequence text) {
        final InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            boolean updateSuggestions;
            switch (type) {
                case InputListener.ENTRY_TYPE_STRING:
                    clearSuggestionsDelayed();
                    if (mEnterSpaceBeforeCommitting && mKeyboardController.enableAutoEnterSpace()) {
                        if (LeanbackUtils.isAlphabet(keyCode)) {
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
                            captureSelectionToClipboardHistory(connection);
                            connection.performContextMenuAction(android.R.id.copy);
                            break;
                        case 3: // Cut
                            captureSelectionToClipboardHistory(connection);
                            connection.performContextMenuAction(android.R.id.cut);
                            mEnterSpaceBeforeCommitting = false;
                            break;
                        case 4: // Paste
                            connection.performContextMenuAction(android.R.id.paste);
                            mEnterSpaceBeforeCommitting = false;
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
        mInputView = mKeyboardController.getView();
        mInputView.requestFocus();

        return mInputView;
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

        // Setting our root view's own LayoutParams (see
        // LeanbackKeyboardContainer) controls how it's placed *inside*
        // the system's input frame, but the actual on-screen window
        // surface itself (its width/gravity as WindowManager sees it) is
        // a separate, higher-level setting - this is the documented hook
        // for it (InputMethodService#onConfigureWindow). Without this,
        // the window keeps spanning the full screen width no matter what
        // our inner view's LayoutParams say.
        boolean isFloating = LeanKeyPreferences.instance(this).isFloatingKeyboard();
        if (isFloating) {
            win.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        } else {
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(android.view.Gravity.BOTTOM);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        boolean currentFloatingState = LeanKeyPreferences.instance(this).isFloatingKeyboard();
        boolean currentLegacyCompatState = LegacyCompat.isEnabled(this);
        if (currentFloatingState != mLastFloatingState || currentLegacyCompatState != mLastLegacyCompatState) {
            onInitializeInterface();
            mInputView = mKeyboardController.getView();
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
