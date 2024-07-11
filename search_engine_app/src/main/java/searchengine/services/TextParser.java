package searchengine.services;

import java.util.HashMap;

public interface TextParser {
    HashMap<String, Integer> getLemmas (String text);
    String replaceHtml(String text);
}
