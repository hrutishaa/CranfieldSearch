package search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentsTest {
    @Test
    void tokenizerSplitsOnPunctuationAndLowercases() {
        assertEquals(List.of("mach", "2", "5", "flow", "past", "a", "cone"), Analyzer.tokenize("Mach 2.5 flow-past a cone."));
    }

    @Test
    void analyzerDropsStopwordsAndStems() {
        assertEquals(List.of("heat", "transfer", "cone"), Analyzer.standard().analyze("The heat transfer to cones"));
    }

    @Test
    void varintRoundTripsEdgeValues() throws IOException {
        int[] values = {0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int v : values) IndexIO.writeVInt(out, v);
        out.flush();
        assertEquals(1 + 1 + 1 + 2 + 2 + 3 + 5, bytes.size());
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        for (int v : values) assertEquals(v, IndexIO.readVInt(in));
    }

    @Test
    void cranfieldIndexSurvivesSaveAndLoad() throws IOException {
        Analyzer a = Analyzer.standard();
        InvertedIndex idx = InvertedIndex.build(Corpus.readDocs(Path.of("data/cran.all.1400")), a);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IndexIO.write(idx, bytes);
        InvertedIndex back = IndexIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertArrayEquals(idx.terms, back.terms);
        assertArrayEquals(idx.docLengths, back.docLengths);
        assertArrayEquals(idx.externalIds, back.externalIds);
        for (int t = 0; t < idx.termCount(); t++) {
            assertArrayEquals(idx.postingDocs[t], back.postingDocs[t]);
            assertArrayEquals(idx.postingTfs[t], back.postingTfs[t]);
            for (int i = 0; i < idx.postingDocs[t].length; i++) {
                assertArrayEquals(idx.postingPositions[t][i], back.postingPositions[t][i]);
            }
        }
        String q = "what problems of heat conduction in composite slabs have been solved so far";
        assertEquals(new Searcher(idx, a).search(q, 10, Scorer.bm25()), new Searcher(back, a).search(q, 10, Scorer.bm25()));
    }

    @Test
    void rejectsGarbageIndexFile() {
        assertThrows(IOException.class, () -> IndexIO.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
    }

    @Test
    void trieSuggestsMostFrequentFirst() {
        PrefixTrie t = new PrefixTrie();
        t.add("super", 2);
        t.add("supersonic", 40);
        t.add("superposition", 5);
        t.add("subsonic", 30);
        t.add("supersonic", 1);
        assertEquals(4, t.size());
        List<PrefixTrie.Suggestion> s = t.suggest("sup", 2);
        assertEquals(List.of(new PrefixTrie.Suggestion("supersonic", 41), new PrefixTrie.Suggestion("superposition", 5)), s);
        assertTrue(t.suggest("xyz", 5).isEmpty());
    }

    @Test
    void metricsOnAKnownRanking() {
        Map<Integer, Integer> rel = Map.of(1, 3, 3, 1, 9, 2); // doc -> grade
        List<Integer> ranked = List.of(1, 2, 3, 4);
        // relevant at ranks 1 and 3: AP = (1/1 + 2/3) / 3 relevant docs
        assertEquals((1 + 2.0 / 3) / 3, Metrics.averagePrecision(ranked, rel), 1e-12);
        assertEquals(0.2, Metrics.precisionAt(ranked, rel, 10), 1e-12);
        assertEquals(2.0 / 3, Metrics.recallAt(ranked, rel, 100), 1e-12);
        double dcg = 7 / 1.0 + 1 / (Math.log(4) / Math.log(2));
        double idcg = 7 / 1.0 + 3 / (Math.log(3) / Math.log(2)) + 1 / 2.0;
        assertEquals(dcg / idcg, Metrics.ndcgAt(ranked, rel, 10), 1e-12);
    }

    @Test
    void cranfieldFilesParse() throws IOException {
        List<Corpus.Doc> docs = Corpus.readDocs(Path.of("data/cran.all.1400"));
        List<Corpus.Query> qs = Corpus.readQueries(Path.of("data/cran.qry"));
        Map<Integer, Map<Integer, Integer>> qrels = Corpus.readQrels(Path.of("data/cranqrel"));
        assertEquals(1400, docs.size());
        assertEquals(225, qs.size());
        assertEquals(365, qs.get(224).originalId()); // ids in the file have gaps
        assertEquals(225, qrels.size());
        assertEquals(1612, qrels.values().stream().mapToInt(Map::size).sum());
        assertTrue(docs.get(0).title().startsWith("experimental investigation of the aerodynamics"));
    }
}
