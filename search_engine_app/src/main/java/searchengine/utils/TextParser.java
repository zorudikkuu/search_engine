package searchengine.utils;

import java.util.HashMap;

public interface TextParser {
    HashMap<String, Integer> getLemmas (String text);
    String getLemma (String word);
    String replaceHtml(String text);
}
