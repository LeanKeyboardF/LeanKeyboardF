package com.EdS.LeanKeyboardF.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JapaneseKanaTest {
    @Test
    public void transformCyclesHiraganaAndKatakana() {
        assertEquals(Character.valueOf('が'), JapaneseKana.next('か'));
        assertEquals(Character.valueOf('か'), JapaneseKana.next('が'));
        assertEquals(Character.valueOf('ガ'), JapaneseKana.next('カ'));
    }

    @Test
    public void kanaCheckIncludesKanaMarksButExcludesPunctuation() {
        assertTrue(JapaneseKana.isKana('ゃ'));
        assertTrue(JapaneseKana.isKana('ー'));
        assertFalse(JapaneseKana.isKana('・'));
        assertFalse(JapaneseKana.isKana('。'));
        assertNull(JapaneseKana.next('・'));
    }
}
