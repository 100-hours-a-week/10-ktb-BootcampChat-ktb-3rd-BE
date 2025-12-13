package com.ktb.chatapp.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.hankcs.algorithm.AhoCorasickDoubleArrayTrie;

public class BannedWordChecker {

    private final AhoCorasickDoubleArrayTrie<String> trie;

    public BannedWordChecker(Set<String> bannedWords) {
        if (bannedWords == null || bannedWords.isEmpty()) {
            throw new IllegalArgumentException("Banned words must not be empty");
        }

        Map<String, String> dict = new HashMap<>();

        for (String word : bannedWords) {
            if (word == null || word.isBlank()) continue;
            dict.put(word.toLowerCase(Locale.ROOT), word);
        }

        trie = new AhoCorasickDoubleArrayTrie<>();
        trie.build(dict);
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);

        return !trie.parseText(normalized).isEmpty();
    }
}
