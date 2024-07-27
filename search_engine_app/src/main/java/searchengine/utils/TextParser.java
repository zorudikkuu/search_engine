package searchengine.utils;

import searchengine.model.entities.Lemma;

import java.util.HashMap;

public interface TextParser {
    HashMap<String, Integer> getLemmas (String text);
    String getLemma (String word, Language language);
    String replaceHtml(String text);
}
