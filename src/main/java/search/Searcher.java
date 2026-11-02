package search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Term-at-a-time scoring into a dense accumulator array, then a size-k min-heap for the top results.
 * Text inside double quotes is a phrase: matching docs must contain those words consecutively.
 * Safe to share across threads: all mutable state is per call.
 */
public final class Searcher {
    public record Hit(int docId, double score, String title) {}

    private final InvertedIndex index;
    private final Analyzer analyzer;

    public Searcher(InvertedIndex index, Analyzer analyzer) {
        if (index.stemmed != analyzer.stems()) {
            throw new IllegalArgumentException("index and query analyzer disagree on stemming");
        }
        this.index = index;
        this.analyzer = analyzer;
    }

    public List<Hit> search(String query, int k, Scorer scorer) {
        List<List<String>> phrases = new ArrayList<>();
        StringBuilder free = new StringBuilder();
        String[] parts = query.split("\"", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                List<String> raw = Analyzer.tokenize(parts[i]);
                if (!raw.isEmpty()) phrases.add(raw);
            }
            free.append(parts[i]).append(' ');
        }

        Map<Integer, Integer> qtf = new HashMap<>();
        for (String t : analyzer.analyze(free.toString())) {
            Integer id = index.termIds.get(t);
            if (id != null) qtf.merge(id, 1, Integer::sum);
        }
        if (qtf.isEmpty()) return List.of();

        int n = index.docCount();
        double[] acc = new double[n];
        boolean[] seen = new boolean[n];
        int[] touched = new int[n];
        int touchedCount = 0;
        for (Map.Entry<Integer, Integer> e : qtf.entrySet()) {
            int t = e.getKey();
            int[] docs = index.postingDocs[t];
            int[] tfs = index.postingTfs[t];
            double idf = scorer.idf(docs.length, n) * e.getValue();
            for (int i = 0; i < docs.length; i++) {
                int d = docs[i];
                if (!seen[d]) {
                    seen[d] = true;
                    touched[touchedCount++] = d;
                }
                acc[d] += idf * scorer.tf(tfs[i], index.docLengths[d], index.avgDocLength);
            }
        }

        boolean[] allowed = null;
        for (List<String> phrase : phrases) {
            boolean[] ok = phraseMatches(phrase);
            if (allowed == null) allowed = ok;
            else for (int d = 0; d < n; d++) allowed[d] &= ok[d];
        }

        PriorityQueue<Hit> heap = new PriorityQueue<>(k + 1, Searcher::worseFirst);
        for (int i = 0; i < touchedCount; i++) {
            int d = touched[i];
            if (allowed != null && !allowed[d]) continue;
            Hit h = new Hit(index.externalIds[d], acc[d], index.titles[d]);
            if (heap.size() < k) heap.add(h);
            else if (worseFirst(h, heap.peek()) > 0) {
                heap.poll();
                heap.add(h);
            }
        }
        List<Hit> out = new ArrayList<>(heap);
        out.sort(Collections.reverseOrder(Searcher::worseFirst));
        return out;
    }

    /** Orders hits worst-first: lower score, then higher doc id (so ties resolve to the lower id). */
    static int worseFirst(Hit a, Hit b) {
        int c = Double.compare(a.score(), b.score());
        return c != 0 ? c : Integer.compare(b.docId(), a.docId());
    }

    /** Positional intersection: doc d matches if term i appears at p + i for every i. */
    boolean[] phraseMatches(List<String> rawTokens) {
        boolean[] ok = new boolean[index.docCount()];
        List<int[]> offsets = new ArrayList<>(); // {termId, offset}
        for (int i = 0; i < rawTokens.size(); i++) {
            String term = analyzer.normalize(rawTokens.get(i));
            if (term == null) continue; // stopwords inside a phrase only hold their position
            Integer id = index.termIds.get(term);
            if (id == null) return ok;
            offsets.add(new int[] {id, i});
        }
        if (offsets.isEmpty()) {
            Arrays.fill(ok, true);
            return ok;
        }
        // Drive from the rarest term to keep the candidate set small.
        offsets.sort((x, y) -> Integer.compare(index.postingDocs[x[0]].length, index.postingDocs[y[0]].length));
        int[] lead = offsets.get(0);
        for (int i = 0; i < index.postingDocs[lead[0]].length; i++) {
            int d = index.postingDocs[lead[0]][i];
            int[] leadPos = index.postingPositions[lead[0]][i];
            for (int p : leadPos) {
                int start = p - lead[1];
                boolean all = true;
                for (int j = 1; j < offsets.size() && all; j++) {
                    int[] o = offsets.get(j);
                    int idx = Arrays.binarySearch(index.postingDocs[o[0]], d);
                    all = idx >= 0 && Arrays.binarySearch(index.postingPositions[o[0]][idx], start + o[1]) >= 0;
                }
                if (all) {
                    ok[d] = true;
                    break;
                }
            }
        }
        return ok;
    }
}
