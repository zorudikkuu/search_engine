package searchengine.services;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
@Component
public class TextParserImpl implements TextParser {
    private LuceneMorphology russianMorphology = null;
    private final static String[] particles = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ"};
    public TextParserImpl () {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    //this method only russian (other code can take english sites)
    @Override
    public HashMap<String, Integer> getLemmas (String text) {
        if (text.isEmpty()) {
            return new HashMap<>();
        }
        String regex = "[^А-Яа-я]";
        String clearedText = replaceHtml(text)
                .replaceAll(regex, " ")
                .toLowerCase()
                .trim();
        String[] words = clearedText.split("\\s+");

        HashMap<String, Integer> lemmas = new HashMap<>();
        for (String word : words) {
            List<String> wordFormsInfo = russianMorphology.getMorphInfo(word);

            if (!wordFormsInfo.stream().allMatch(TextParserImpl::isIndependent) || word.isBlank()) {
                continue;
            }

            List<String> normalForms = russianMorphology.getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            for (String lemma : normalForms) {
                if (lemmas.containsKey(lemma)) {
                    lemmas.put(lemma, lemmas.get(lemma) + 1);
                } else {
                    lemmas.put(lemma, 1);
                }
            }
        }
        return lemmas;
    }

    @Override
    public String replaceHtml(String text) {
        String startTag = "<script";
        String endTag = "</script>";

        while (text.contains(startTag)) {
            int startIndex = text.lastIndexOf(startTag);
            int endIndex = text.indexOf(endTag,startIndex);

            String script = text.substring(startIndex,endIndex + endTag.length());
            text = text.replace(script," ");
        }


        return text.replaceAll("<(.|\n)*?>", " ");
    }

    private static boolean isIndependent(String wordInfo) {
        for (String particle : particles) {
            if (wordInfo.toUpperCase().contains(particle)) {
                return false;
            }
        }
        return true;
    }
}

