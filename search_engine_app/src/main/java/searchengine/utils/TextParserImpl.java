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
    private LuceneMorphology englishMorphology = null;
    private final static String[] russianParticles = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ"};
    private final static String[] englishParticles = new String[]{"PREP", "CONJ", "PART", "INT", "ARTICLE"};
    public TextParserImpl () {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorphology = new EnglishLuceneMorphology();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    @Override
    public HashMap<String, Integer> getLemmas (String text) {
        if (text.isEmpty()) {
            return new HashMap<>();
        }

        String[] russianWords = getWords(text, Language.RUSSIAN);
        String[] englishWords = getWords(text, Language.ENGLISH);

        HashMap<String, Integer> lemmas = new HashMap<>();
        lemmas.putAll(getLemmaMap(russianWords, Language.RUSSIAN));
        lemmas.putAll(getLemmaMap(englishWords, Language.ENGLISH));

        return lemmas;
    }

    private HashMap<String, Integer> getLemmaMap (String[] words, Language language) {
        HashMap<String, Integer> lemmas = new HashMap<>();
        LuceneMorphology morphology;
        if (language == Language.RUSSIAN) {
            morphology = russianMorphology;
        } else {
            morphology = englishMorphology;
        }
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            List<String> wordFormsInfo = morphology.getMorphInfo(word);

            if (!wordFormsInfo.stream().allMatch(info -> isIndependent(info, language))) {
                continue;
            }

            List<String> normalForms = morphology.getNormalForms(word);
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

    public String getLemma (String word, Language language) {
        LuceneMorphology morphology = language == Language.RUSSIAN ? russianMorphology : englishMorphology;
        return morphology.getNormalForms(word).get(0);
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

    private String[] getWords (String text, Language language) {
        String regex = language == Language.RUSSIAN ? "[^А-Яа-я]" : "[^A-Za-z]";
        String clearedText = replaceHtml(text)
                .replaceAll(regex, " ")
                .toLowerCase()
                .trim();
        return clearedText.split("\\s+");
    }

    private static boolean isIndependent (String wordInfo, Language language) {
        String[] particles = language == Language.RUSSIAN ? russianParticles : englishParticles;
        for (String particle : particles) {
            if (wordInfo.toUpperCase().contains(particle)) {
                return false;
            }
        }
        return true;
    }
}

