package searchengine.utils;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

@Component
public class TextParserImpl implements TextParser {
    private LuceneMorphology russianMorphology = null;
    private LuceneMorphology englishMorfology = null;
    private final static String[] russianParticles = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ"};
    private final static String[] englishParticles = new String[]{"PREP", "CONJ", "PART", "INT", "ARTICLE"};
    public TextParserImpl () {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorfology = new EnglishLuceneMorphology();
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

        String[] russianWords = getRussianWords(text);

        HashMap<String, Integer> russianLemmas = getLemmaMap(russianWords);

        return russianLemmas;
    }

    private HashMap<String, Integer> getLemmaMap (String[] words) {
        HashMap<String, Integer> lemmas = new HashMap<>();
        for (String word : words) {
            List<String> wordFormsInfo = russianMorphology.getMorphInfo(word);

            if (!wordFormsInfo.stream().allMatch(info -> isIndependent(info) || word.isBlank())) {
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

    public String getLemma (String word) {
        return russianMorphology.getNormalForms(word).get(0);
    }

    @Override
    public String replaceHtml(String text) {
        text = removeTagContent("script", text);
        text = removeTagContent("style", text);

        return text.replaceAll("<(.|\n)*?>", " ");
    }

    private String removeTagContent (String tag, String text) {
        String startTag = "<" + tag;
        String endTag = "</" + tag + ">";

        while (text.contains(startTag)) {
            int startIndex = text.lastIndexOf(startTag);
            int endIndex = text.indexOf(endTag,startIndex);

            String content = text.substring(startIndex,endIndex + endTag.length());
            text = text.replace(content," ");
        }
        return text;
    }

    private String[] getRussianWords (String text) {
        String regexRussian = "[^А-Яа-я]";
        String clearedText = replaceHtml(text)
                .replaceAll(regexRussian, " ")
                .toLowerCase()
                .trim();
        return clearedText.split("\\s+");
    }

    private String[] getEnglishWords (String text) {
        String regexEnglish = "[^A-Za-z]";
        String clearedText = replaceHtml(text)
                .replaceAll(regexEnglish, " ")
                .toLowerCase()
                .trim();
        return clearedText.split("\\s+");
    }

    private static boolean isIndependent(String wordInfo) {
        for (String particle : russianParticles) {
            if (wordInfo.toUpperCase().contains(particle)) {
                return false;
            }
        }
        return true;
    }
}

