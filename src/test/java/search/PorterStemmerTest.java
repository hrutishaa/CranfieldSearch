package search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PorterStemmerTest {
    @Test
    void examplesFromThePaper() {
        PorterStemmer s = new PorterStemmer();
        assertEquals("caress", s.stem("caresses"));
        assertEquals("poni", s.stem("ponies"));
        assertEquals("relat", s.stem("relational"));
        assertEquals("hope", s.stem("hoping"));
        assertEquals("hop", s.stem("hopping"));
        assertEquals("gener", s.stem("generalization"));
        assertEquals("aerodynam", s.stem("aerodynamics"));
    }

    /** Martin Porter's published 23,531-word vocabulary and the expected output of his reference implementation. */
    @Test
    void matchesReferenceVocabulary() throws Exception {
        PorterStemmer s = new PorterStemmer();
        List<String> wrong = new ArrayList<>();
        int total = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/porter-vocabulary.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split("\t");
                total++;
                if (!s.stem(p[0]).equals(p[1])) wrong.add(p[0] + " -> " + s.stem(p[0]) + " (want " + p[1] + ")");
            }
        }
        assertEquals(23531, total);
        assertTrue(wrong.isEmpty(), wrong.size() + " mismatches, e.g. " + wrong.subList(0, Math.min(5, wrong.size())));
    }
}
