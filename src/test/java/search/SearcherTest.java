package search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearcherTest {
    static final List<Corpus.Doc> DOCS = List.of(
        new Corpus.Doc(10, "boundary layer", "", "laminar boundary layer on a flat plate"),
        new Corpus.Doc(20, "shock waves", "", "shock waves and the boundary of the layer"),
        new Corpus.Doc(30, "flat plates", "", "heat transfer to a flat plate in supersonic flow flow flow"),
        new Corpus.Doc(40, "unrelated", "", "propeller noise measurements"));

    final Analyzer analyzer = new Analyzer(false, true);
    final InvertedIndex index = InvertedIndex.build(DOCS, analyzer);
    final Searcher searcher = new Searcher(index, analyzer);

    @Test
    void indexStatistics() {
        assertEquals(4, index.docCount());
        assertEquals(2, index.docFreq("boundary"));
        assertEquals(0, index.docFreq("the")); // stopword
        // doc 30: flat plates heat transfer flat plate supersonic flow flow flow = 10 kept terms
        assertEquals(10, index.docLengths[2]);
    }

    @Test
    void bm25ScoreMatchesHandCalculation() {
        Scorer bm25 = Scorer.bm25(1.2, 0.75);
        List<Searcher.Hit> hits = searcher.search("propeller", 10, bm25);
        assertEquals(1, hits.size());
        double idf = Math.log(1 + (4 - 1 + 0.5) / (1 + 0.5));
        double dl = 4; // unrelated propeller noise measurements
        double tf = 1 * 2.2 / (1 + 1.2 * (1 - 0.75 + 0.75 * dl / index.avgDocLength()));
        assertEquals(idf * tf, hits.get(0).score(), 1e-12);
    }

    @Test
    void ranksDocsWithMoreMatchingTermsHigher() {
        List<Searcher.Hit> hits = searcher.search("laminar boundary layer", 10, Scorer.bm25());
        assertEquals(10, hits.get(0).docId());
        assertEquals(20, hits.get(1).docId());
        assertEquals(2, hits.size());
    }

    @Test
    void topKKeepsOnlyTheBestAndIsSorted() {
        List<Searcher.Hit> all = searcher.search("flat plate boundary flow", 10, Scorer.bm25());
        List<Searcher.Hit> top2 = searcher.search("flat plate boundary flow", 2, Scorer.bm25());
        assertEquals(all.subList(0, 2), top2);
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1).score() >= all.get(i).score());
    }

    @Test
    void tiesBreakTowardLowerDocId() {
        Analyzer a = new Analyzer(false, false);
        InvertedIndex idx = InvertedIndex.build(List.of(
            new Corpus.Doc(7, "", "", "wing"), new Corpus.Doc(3, "", "", "wing")), a);
        List<Searcher.Hit> hits = new Searcher(idx, a).search("wing", 1, Scorer.bm25());
        assertEquals(3, hits.get(0).docId());
    }

    @Test
    void phraseRequiresAdjacentWords() {
        // "boundary layer" is adjacent in doc 10 but not in doc 20 ("boundary of the layer")
        List<Searcher.Hit> hits = searcher.search("\"boundary layer\"", 10, Scorer.bm25());
        assertEquals(List.of(10), hits.stream().map(Searcher.Hit::docId).toList());
    }

    @Test
    void stopwordInsidePhraseKeepsItsSlot() {
        // "boundary of the layer" matches doc 20 only, because the stopwords still occupy positions
        List<Searcher.Hit> hits = searcher.search("\"boundary of the layer\"", 10, Scorer.bm25());
        assertEquals(List.of(20), hits.stream().map(Searcher.Hit::docId).toList());
        assertTrue(searcher.search("\"boundary the layer\"", 10, Scorer.bm25()).isEmpty());
    }

    @Test
    void unknownWordsReturnNothing() {
        assertTrue(searcher.search("hovercraft", 10, Scorer.bm25()).isEmpty());
        assertTrue(searcher.search("", 10, Scorer.bm25()).isEmpty());
    }

    @Test
    void refusesMismatchedAnalyzer() {
        assertThrows(IllegalArgumentException.class, () -> new Searcher(index, Analyzer.standard()));
    }

    @Test
    void tfIdfPrefersRepeatedTerm() {
        List<Searcher.Hit> hits = searcher.search("flow", 10, Scorer.tfIdf());
        assertEquals(30, hits.get(0).docId());
    }
}
