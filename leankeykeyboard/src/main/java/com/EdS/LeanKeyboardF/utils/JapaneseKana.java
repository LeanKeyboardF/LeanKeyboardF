package com.EdS.LeanKeyboardF.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Helpers for the Japanese layouts (qwerty_ja.xml - hiragana, abc_ja.xml -
 * katakana).
 *
 * The layouts have a key "゛゜" (code {@link #KEYCODE_TRANSFORM}) that does not
 * type a character of its own: it changes the character just before the
 * cursor to its next form, like the "゛゜小" key of Japanese phone keyboards:
 * plain -> voiced (dakuten) -> semi-voiced (handakuten) / small ->
 * back to plain.
 *
 *   か -> が -> か        は -> ば -> ぱ -> は        つ -> っ -> づ -> つ
 *   や -> ゃ -> や        う -> ぅ -> ゔ -> う        ア -> ァ -> ア ...
 *
 * Katakana is derived from the hiragana table (same layout of the Unicode
 * blocks, katakana = hiragana + 0x60), so both scripts always behave alike.
 */
public final class JapaneseKana {
    /** Key code of the "゛゜" key in the layout xml files. */
    public static final int KEYCODE_TRANSFORM = -20;

    // One string per kana: the characters are its forms in cycling order.
    private static final String[] HIRAGANA_CYCLES = {
            "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
            "かが", "きぎ", "くぐ", "けげ", "こご",
            "さざ", "しじ", "すず", "せぜ", "そぞ",
            "ただ", "ちぢ", "つっづ", "てで", "とど",
            "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
            "やゃ", "ゆゅ", "よょ", "わゎ",
    };

    private static final int HIRAGANA_FIRST = 0x3041;
    private static final int HIRAGANA_LAST = 0x3096;
    private static final int KATAKANA_OFFSET = 0x60;

    private static final Map<Character, Character> NEXT = new HashMap<>();

    static {
        for (String cycle : HIRAGANA_CYCLES) {
            for (int i = 0; i < cycle.length(); i++) {
                char from = cycle.charAt(i);
                char to = cycle.charAt((i + 1) % cycle.length());
                NEXT.put(from, to);
                NEXT.put(toKatakana(from), toKatakana(to));
            }
        }
    }

    private JapaneseKana() {
    }

    /**
     * The next form of the given kana in its cycle, or null if the character
     * has no other forms (or is not kana at all).
     */
    public static Character next(char c) {
        return NEXT.get(c);
    }

    /** True for hiragana and katakana (including the long-vowel mark). */
    public static boolean isKana(int codePoint) {
        return codePoint >= 0x3041 && codePoint <= 0x30FF;
    }

    private static char toKatakana(char hiragana) {
        if (hiragana >= HIRAGANA_FIRST && hiragana <= HIRAGANA_LAST) {
            return (char) (hiragana + KATAKANA_OFFSET);
        }
        return hiragana;
    }
}
