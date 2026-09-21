package com.EdS.LeanKeyboardF.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Replaces the old "suggestions" (which only ever worked for email domains,
 * or if the target app happened to provide its own completions - i.e.
 * almost never) with a personal dictionary the keyboard builds from what
 * the user actually types.
 *
 * Two things are learned as the user types:
 * - word frequency: how many times each word has been typed, used to rank
 *   completions while a word is being typed (prefix match)
 * - bigrams: for each word, which word most often follows it, used to
 *   suggest the next word right after finishing one (a lightweight,
 *   scoped form of "sentence" prediction - it predicts one likely next
 *   word at a time, built up from real usage, rather than storing whole
 *   sentences verbatim)
 *
 * Both are capped and pruned (lowest-frequency entries dropped first) so
 * storage can't grow without bound.
 */
public class LearningDictionary {
    private static final String PREFS_NAME = "learning_dictionary";
    private static final String KEY_WORDS = "words";
    private static final String KEY_BIGRAMS = "bigrams";
    private static final int MAX_WORDS = 1000;
    private static final int MAX_BIGRAM_KEYS = 500;
    private static final int MAX_BIGRAM_TARGETS_PER_KEY = 20;
    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 40;

    private static LearningDictionary sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;

    public static synchronized LearningDictionary instance(Context context) {
        if (sInstance == null) {
            sInstance = new LearningDictionary(context.getApplicationContext());
        }
        return sInstance;
    }

    private LearningDictionary(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // List.sort() and Comparator.comparingInt() only exist from Android 7
    // (API 24) - on Android 4-6 they throw NoSuchMethodError the first time
    // a suggestion is computed, which kills the keyboard service. With
    // "Legacy Android mode" on, sort with Collections.sort() instead. With
    // it off, this is the same code as before.
    private void sortByValue(List<Map.Entry<String, Integer>> entries, boolean descending) {
        if (LegacyCompat.isEnabled(mContext)) {
            LegacyCompat.sortEntriesByValue(entries, descending);
        } else if (descending) {
            entries.sort((a, b) -> b.getValue() - a.getValue());
        } else {
            entries.sort(Comparator.comparingInt(Map.Entry::getValue));
        }
    }

    public synchronized void learnWord(String word) {
        if (word == null) {
            return;
        }

        word = word.trim().toLowerCase();
        if (word.length() < MIN_WORD_LENGTH || word.length() > MAX_WORD_LENGTH) {
            return;
        }

        if (!containsLetter(word)) {
            return;
        }

        try {
            JSONObject words = new JSONObject(mPrefs.getString(KEY_WORDS, "{}"));
            words.put(word, words.optInt(word, 0) + 1);

            if (words.length() > MAX_WORDS) {
                pruneLowestFrequency(words, MAX_WORDS);
            }

            mPrefs.edit().putString(KEY_WORDS, words.toString()).apply();
        } catch (JSONException e) {
            resetWords();
        }
    }

    private boolean containsLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public synchronized void learnBigram(String prevWord, String nextWord) {
        if (prevWord == null || nextWord == null) {
            return;
        }

        prevWord = prevWord.trim().toLowerCase();
        nextWord = nextWord.trim().toLowerCase();
        if (prevWord.isEmpty() || nextWord.isEmpty()) {
            return;
        }

        try {
            JSONObject bigrams = new JSONObject(mPrefs.getString(KEY_BIGRAMS, "{}"));
            JSONObject targets = bigrams.optJSONObject(prevWord);
            if (targets == null) {
                targets = new JSONObject();
            }

            targets.put(nextWord, targets.optInt(nextWord, 0) + 1);

            if (targets.length() > MAX_BIGRAM_TARGETS_PER_KEY) {
                pruneLowestFrequency(targets, MAX_BIGRAM_TARGETS_PER_KEY);
            }

            bigrams.put(prevWord, targets);

            if (bigrams.length() > MAX_BIGRAM_KEYS) {
                pruneLowestFrequencyBigramKeys(bigrams, MAX_BIGRAM_KEYS);
            }

            mPrefs.edit().putString(KEY_BIGRAMS, bigrams.toString()).apply();
        } catch (JSONException e) {
            resetBigrams();
        }
    }

    // Ranked (highest frequency first) words starting with prefix - used
    // while a word is being typed.
    public synchronized List<String> getPrefixSuggestions(String prefix, int max) {
        List<String> result = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return result;
        }

        String lowerPrefix = prefix.toLowerCase();

        try {
            JSONObject words = new JSONObject(mPrefs.getString(KEY_WORDS, "{}"));
            List<Map.Entry<String, Integer>> matches = new ArrayList<>();
            Iterator<String> keys = words.keys();
            while (keys.hasNext()) {
                String word = keys.next();
                if (!word.equalsIgnoreCase(prefix) && word.toLowerCase().startsWith(lowerPrefix)) {
                    matches.add(new AbstractMap.SimpleEntry<>(word, words.optInt(word, 0)));
                }
            }

            sortByValue(matches, true);
            for (int i = 0; i < Math.min(max, matches.size()); i++) {
                result.add(matches.get(i).getKey());
            }
        } catch (JSONException e) {
            resetWords();
        }

        return result;
    }

    // Ranked (highest frequency first) words that have followed prevWord
    // before - used right after finishing a word, before the next one is
    // typed.
    public synchronized List<String> getNextWordSuggestions(String prevWord, int max) {
        List<String> result = new ArrayList<>();
        if (prevWord == null || prevWord.isEmpty()) {
            return result;
        }

        prevWord = prevWord.trim().toLowerCase();

        try {
            JSONObject bigrams = new JSONObject(mPrefs.getString(KEY_BIGRAMS, "{}"));
            JSONObject targets = bigrams.optJSONObject(prevWord);
            if (targets == null) {
                return result;
            }

            List<Map.Entry<String, Integer>> matches = new ArrayList<>();
            Iterator<String> keys = targets.keys();
            while (keys.hasNext()) {
                String word = keys.next();
                matches.add(new AbstractMap.SimpleEntry<>(word, targets.optInt(word, 0)));
            }

            sortByValue(matches, true);
            for (int i = 0; i < Math.min(max, matches.size()); i++) {
                result.add(matches.get(i).getKey());
            }
        } catch (JSONException e) {
            resetBigrams();
        }

        return result;
    }

    public synchronized void clear() {
        mPrefs.edit().clear().apply();
    }

    private void resetWords() {
        mPrefs.edit().remove(KEY_WORDS).apply();
    }

    private void resetBigrams() {
        mPrefs.edit().remove(KEY_BIGRAMS).apply();
    }

    private void pruneLowestFrequency(JSONObject map, int maxSize) throws JSONException {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            entries.add(new AbstractMap.SimpleEntry<>(key, map.optInt(key, 0)));
        }

        sortByValue(entries, false);

        int toRemove = entries.size() - maxSize;
        for (int i = 0; i < toRemove; i++) {
            map.remove(entries.get(i).getKey());
        }
    }

    private void pruneLowestFrequencyBigramKeys(JSONObject bigrams, int maxSize) throws JSONException {
        List<Map.Entry<String, Integer>> keyTotals = new ArrayList<>();
        Iterator<String> keys = bigrams.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject targets = bigrams.optJSONObject(key);
            int total = 0;
            if (targets != null) {
                Iterator<String> targetKeys = targets.keys();
                while (targetKeys.hasNext()) {
                    total += targets.optInt(targetKeys.next(), 0);
                }
            }
            keyTotals.add(new AbstractMap.SimpleEntry<>(key, total));
        }

        sortByValue(keyTotals, false);

        int toRemove = keyTotals.size() - maxSize;
        for (int i = 0; i < toRemove; i++) {
            bigrams.remove(keyTotals.get(i).getKey());
        }
    }
}
