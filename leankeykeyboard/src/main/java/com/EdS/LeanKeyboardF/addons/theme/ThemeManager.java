package com.EdS.LeanKeyboardF.addons.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import com.EdS.LeanKeyboardF.ime.LeanbackKeyboardView;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;
import com.EdS.LeanKeyboardF.utils.LegacyCompat;
import com.EdS.LeanKeyboardF.R;

import java.util.Locale;

public class ThemeManager {
    private static final String TAG = ThemeManager.class.getSimpleName();
    private final Context mContext;
    private final RelativeLayout mRootView;
    private final LeanKeyPreferences mPrefs;

    public ThemeManager(Context context, RelativeLayout rootView) {
        mContext = context;
        mRootView = rootView;
        mPrefs = LeanKeyPreferences.instance(mContext);
    }

    public void updateKeyboardTheme() {
        String rawTheme = mPrefs.getCurrentTheme();

        if (LeanKeyPreferences.THEME_DYNAMIC.equals(rawTheme) && supportsDynamicColor()) {
            applyDynamicKeyboardColors();
            return;
        }

        String themeId = resolveEffectiveThemeId(rawTheme);

        if (LeanKeyPreferences.THEME_DEFAULT.equals(themeId)) {
            applyKeyboardColors(
                    R.color.keyboard_background,
                    R.color.candidate_background,
                    R.color.enter_key_font_color,
                    R.color.key_text_default
            );
            applyShiftDrawable(-1);
        } else {
            Resources resources = mContext.getResources();
            String resourceThemeId = themeId.toLowerCase(Locale.ROOT);
            int keyboardBackgroundResId = resources.getIdentifier("keyboard_background_" + resourceThemeId, "color", mContext.getPackageName());
            int candidateBackgroundResId = resources.getIdentifier("candidate_background_" + resourceThemeId, "color", mContext.getPackageName());
            int enterFontColorResId = resources.getIdentifier("enter_key_font_color_" + resourceThemeId, "color", mContext.getPackageName());
            int keyTextColorResId = resources.getIdentifier("key_text_default_" + resourceThemeId, "color", mContext.getPackageName());

            applyKeyboardColors(
                    keyboardBackgroundResId,
                    candidateBackgroundResId,
                    enterFontColorResId,
                    keyTextColorResId
            );

            int shiftLockOnResId = resources.getIdentifier("ic_ime_shift_lock_on_" + resourceThemeId, "drawable", mContext.getPackageName());

            applyShiftDrawable(shiftLockOnResId);
        }
    }

    public void updateSuggestionsTheme() {
        String rawTheme = mPrefs.getCurrentTheme();

        if (LeanKeyPreferences.THEME_DYNAMIC.equals(rawTheme) && supportsDynamicColor()) {
            applyDynamicSuggestionsColors();
            return;
        }

        String themeId = resolveEffectiveThemeId(rawTheme);

        if (LeanKeyPreferences.THEME_DEFAULT.equals(themeId)) {
            applySuggestionsColors(R.color.candidate_font_color);
        } else {
            Resources resources = mContext.getResources();
            int candidateFontColorResId = resources.getIdentifier("candidate_font_color_" + themeId.toLowerCase(Locale.ROOT), "color", mContext.getPackageName());
            applySuggestionsColors(candidateFontColorResId);
        }
    }

    // "System" follows the device's current light/dark UI setting - it's
    // resolved to one of the concrete themes below, not a color set of
    // its own. "Dynamic Color" is handled separately (applyDynamic*),
    // since it doesn't come from a color resource at all.
    private String resolveEffectiveThemeId(String rawTheme) {
        if (LeanKeyPreferences.THEME_SYSTEM.equals(rawTheme)) {
            return isSystemInDarkMode() ? LeanKeyPreferences.THEME_DARK3 : LeanKeyPreferences.THEME_LIGHT;
        }

        if (LeanKeyPreferences.THEME_DYNAMIC.equals(rawTheme)) {
            // Dynamic Color isn't available on this device's API level -
            // fall back to the default look.
            return LeanKeyPreferences.THEME_DEFAULT;
        }

        if (LeanKeyPreferences.THEME_DEFAULT.equals(rawTheme)
                || LeanKeyPreferences.THEME_DARK.equals(rawTheme)
                || LeanKeyPreferences.THEME_DARK2.equals(rawTheme)
                || LeanKeyPreferences.THEME_DARK3.equals(rawTheme)
                || LeanKeyPreferences.THEME_LIGHT.equals(rawTheme)) {
            return rawTheme;
        }

        // Invalid/old preference values must not resolve resource id 0 and
        // crash keyboard inflation. Keep the UI usable with its default theme.
        return LeanKeyPreferences.THEME_DEFAULT;
    }

    private boolean isSystemInDarkMode() {
        int nightModeFlags = mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean supportsDynamicColor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    // Material You: colors generated by the system from the user's
    // wallpaper (Android 12+). Falls back to the Default theme on older
    // devices (see resolveEffectiveThemeId).
    private void applyDynamicKeyboardColors() {
        applyKeyboardColors(
                android.R.color.system_neutral1_900,
                android.R.color.system_neutral1_800,
                android.R.color.system_accent1_200,
                android.R.color.system_neutral1_50
        );
        applyShiftDrawable(-1);
    }

    private void applyDynamicSuggestionsColors() {
        applySuggestionsColors(android.R.color.system_accent1_200);
    }

    private void applyKeyboardColors(
            int keyboardBackground,
            int candidateBackground,
            int enterFontColor,
            int keyTextColor) {

        RelativeLayout rootLayout = mRootView.findViewById(R.id.root_ime);

        if (rootLayout != null) {
            int backgroundColor = ContextCompat.getColor(mContext, keyboardBackground);
            Drawable background = rootLayout.getBackground();

            if (background instanceof GradientDrawable) {
                // Floating keyboard: the background is a rounded shape with
                // a border. setBackgroundColor() would replace it with a
                // flat rectangle, so recolor the shape and keep its corners.
                GradientDrawable shape = (GradientDrawable) background.mutate();
                shape.setColor(backgroundColor);

                int borderColor = (ContextCompat.getColor(mContext, keyTextColor) & 0x00FFFFFF) | 0x80000000;
                int borderWidth = mContext.getResources().getDimensionPixelSize(R.dimen.floating_keyboard_border_width);
                shape.setStroke(borderWidth, borderColor);
            } else {
                rootLayout.setBackgroundColor(backgroundColor);
            }
        }

        View candidateLayout = mRootView.findViewById(R.id.candidate_background);

        if (candidateLayout != null) {
            candidateLayout.setBackgroundColor(ContextCompat.getColor(mContext, candidateBackground));
        }

        Button enterButton = mRootView.findViewById(R.id.enter);

        if (enterButton != null) {
            enterButton.setTextColor(ContextCompat.getColor(mContext, enterFontColor));
        }

        LeanbackKeyboardView keyboardView = mRootView.findViewById(R.id.main_keyboard);

        if (keyboardView != null) {
            keyboardView.setKeyTextColor(ContextCompat.getColor(mContext, keyTextColor));
        }

        int[] actionButtonIds = {
                R.id.action_clear, R.id.action_select_all, R.id.action_copy,
                R.id.action_cut, R.id.action_paste, R.id.action_move
        };

        for (int id : actionButtonIds) {
            ImageButton actionButton = mRootView.findViewById(id);

            if (actionButton != null) {
                int tint = ContextCompat.getColor(mContext, keyTextColor);

                if (LegacyCompat.isEnabled(mContext)) {
                    // ImageViewCompat.setImageTintList() is a silent no-op
                    // below API 21 for a plain ImageButton (the icon would
                    // stay white on any theme) - a colour filter does the
                    // same job on every version.
                    actionButton.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
                } else {
                    ImageViewCompat.setImageTintList(actionButton, android.content.res.ColorStateList.valueOf(tint));
                }
            }
        }
    }

    private void applySuggestionsColors(int candidateFontColor) {
        LinearLayout suggestions = mRootView.findViewById(R.id.suggestions);

        if (suggestions != null) {
            int childCount = suggestions.getChildCount();

            Log.d(TAG, "Number of suggestions: " + childCount);

            for (int i = 0; i < childCount; i++) {
                View child = suggestions.getChildAt(i);

                Button candidateButton = child.findViewById(R.id.text);

                if (candidateButton != null) {
                    candidateButton.setTextColor(ContextCompat.getColor(mContext, candidateFontColor));
                }
            }
        }
    }

    private void applyShiftDrawable(int resId) {
        LeanbackKeyboardView keyboardView = mRootView.findViewById(R.id.main_keyboard);

        if (keyboardView != null && resId > 0) {
            Drawable drawable = ContextCompat.getDrawable(mContext, resId);

            keyboardView.setCapsLockDrawable(drawable);
        }
    }
}
