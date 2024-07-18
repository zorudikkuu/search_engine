import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;

import java.io.IOException;

public class Test {

    public static void main(String[] args) throws IOException {
        LuceneMorphology morphology = new EnglishLuceneMorphology();
        System.out.println(morphology.getMorphInfo("an"));
    }
}
