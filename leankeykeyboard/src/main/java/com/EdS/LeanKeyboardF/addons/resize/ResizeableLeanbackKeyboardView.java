package com.EdS.LeanKeyboardF.addons.resize;

import android.content.Context;
import android.content.res.Resources;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.Keyboard.Key;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import com.EdS.LeanKeyboardF.R;
import com.EdS.LeanKeyboardF.ime.LeanbackKeyboardView;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;

import java.util.Map;
import java.util.WeakHashMap;

public class ResizeableLeanbackKeyboardView extends LeanbackKeyboardView {
    // How much each Keyboard object has already been scaled in place. Keyboard
    // objects can outlive a view, so the scale is remembered with the object
    // itself - scaling is then always "from what it is now to what it should
    // be", never compounded (1.3 and then 1.5 must not become 1.95).
    private static final Map<Keyboard, Float> sAppliedScale = new WeakHashMap<>();

    private final LeanKeyPreferences mPrefs;
    private final int mKeyTextSizeOrigin;
    private final int mModeChangeTextSizeOrigin;
    // What the user asked for (Interface -> Size). Read once per view: the IME
    // rebuilds its views when the size level changes.
    private final float mRequestedFactor;

    public ResizeableLeanbackKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPrefs = LeanKeyPreferences.instance(getContext());
        mRequestedFactor = mPrefs.getKeyboardSizeFactor();
        mKeyTextSizeOrigin = mKeyTextSize;
        mModeChangeTextSizeOrigin = mModeChangeTextSize;
    }

    @Override
    public void setKeyboard(Keyboard keyboard) {
        // The size that really is used: the requested one, but never more than
        // fits on this screen (see fitFactor()). A keyboard wider than the
        // screen was simply cut off on the right.
        float factor = mRequestedFactor > 1.0f ? fitFactor(keyboard) : 1.0f;
        mSizeScale = factor;

        if (factor > 1.0f) {
            mKeyTextSize = (int) (mKeyTextSizeOrigin * factor);
            mModeChangeTextSize = (int) (mModeChangeTextSizeOrigin * factor);
        } else {
            mKeyTextSize = mKeyTextSizeOrigin;
            mModeChangeTextSize = mModeChangeTextSizeOrigin;
        }

        keyboard = updateKeyboard(keyboard, factor);

        mPaint.setTextSize(mKeyTextSize);

        super.setKeyboard(keyboard);
    }

    private Keyboard updateKeyboard(Keyboard keyboard, float factor) {
        applyScale(keyboard, factor);

        if (factor <= 1.0f) {
            return keyboard;
        }

        KeyboardWrapper wrapper = KeyboardWrapper.from(keyboard, getContext());
        wrapper.setHeightFactor(factor);
        wrapper.setWidthFactor(factor);

        return wrapper;
    }

    // Scales the keys of this Keyboard object in place, relative to how much
    // they were scaled before.
    private static void applyScale(Keyboard keyboard, float target) {
        Float applied = sAppliedScale.get(keyboard);
        float from = applied == null ? 1.0f : applied;

        if (Math.abs(target - from) < 0.001f) {
            return;
        }

        float ratio = target / from;

        for (Key key : keyboard.getKeys()) {
            key.width = Math.round(key.width * ratio);
            key.height = Math.round(key.height * ratio);
            key.gap = Math.round(key.gap * ratio);
            key.x = Math.round(key.x * ratio);
            key.y = Math.round(key.y * ratio);
        }

        sAppliedScale.put(keyboard, target);
    }

    // The largest factor (up to the requested one, never below 1) at which the
    // whole panel still fits the screen width: the keys scale, but the action
    // buttons and the Enter button next to them do not, so their width is
    // reserved first. The normal keyboard is centred, so it needs that room on
    // both sides; the floating panel only on one.
    private float fitFactor(Keyboard keyboard) {
        // getMinWidth() of a plain Keyboard is its original, unscaled width.
        float keysWidth = keyboard.getMinWidth();
        if (keysWidth <= 0) {
            return mRequestedFactor;
        }

        Resources res = getResources();
        DisplayMetrics metrics = res.getDisplayMetrics();
        float density = metrics.density;

        // Enter has a guaranteed minimum width (enter_key_min_width); a label
        // longer than that is rare and is covered by the margin below.
        float side = res.getDimension(R.dimen.action_button_width)
                + res.getDimension(R.dimen.enter_key_min_width);
        float margin = 16 * density;

        float available;
        if (mPrefs.isFloatingKeyboard()) {
            float padding = 2 * res.getDimension(R.dimen.floating_keyboard_horizontal_padding);
            available = metrics.widthPixels - padding - side - margin;
        } else {
            available = metrics.widthPixels - 2 * side - margin;
        }

        float fit = available / keysWidth;
        return Math.max(1.0f, Math.min(mRequestedFactor, fit));
    }
}
