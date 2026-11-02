package search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Positional inverted index held in plain arrays. For each term: sorted internal doc ids,
 * term frequency per doc, and the token positions in each doc (needed for phrase queries).
 */
public final class InvertedIndex {
    final Map<String, Integer> termIds;
    final String[] terms;
    final int[][] postingDocs;
    final int[][] postingTfs;
    final int[][][] postingPositions;
    final int[] docLengths;
    final int[] externalIds;
    final String[] titles;
    final double avgDocLength;
    final boolean stemmed;

    InvertedIndex(String[] terms, int[][] docs, int[][] tfs, int[][][] positions,
                  int[] docLengths, int[] externalIds, String[] titles, boolean stemmed) {
        this.terms = terms;
        this.postingDocs = docs;
        this.postingTfs = tfs;
        this.postingPositions = positions;
        this.docLengths = docLengths;
        this.externalIds = externalIds;
        this.titles = titles;
        this.stemmed = stemmed;
        this.termIds = new HashMap<>(terms.length * 2);
        for (int i = 0; i < terms.length; i++) termIds.put(terms[i], i);
        long total = 0;
        for (int l : docLengths) total += l;
        this.avgDocLength = docLengths.length == 0 ? 0 : (double) total / docLengths.length;
    }

    /** Single pass over the corpus; terms are sorted at the end so the on-disk format is stable. */
    public static InvertedIndex build(List<Corpus.Doc> docs, Analyzer analyzer) {
        Map<String, List<int[]>> postings = new HashMap<>(); // term -> [doc, pos, pos, ...] per doc
        int n = docs.size();
        int[] lengths = new int[n];
        int[] ext = new int[n];
        String[] titles = new String[n];
        for (int d = 0; d < n; d++) {
            Corpus.Doc doc = docs.get(d);
            ext[d] = doc.id();
            titles[d] = doc.title();
            List<String> tokens = Analyzer.tokenize(doc.body());
            Map<String, List<Integer>> local = new HashMap<>();
            int kept = 0;
            for (int pos = 0; pos < tokens.size(); pos++) {
                String term = analyzer.normalize(tokens.get(pos));
                if (term == null) continue;
                kept++;
                // Positions count raw tokens, so "flow over a flat plate" keeps its gaps
                // and a phrase query can't match across a dropped stopword by accident.
                local.computeIfAbsent(term, t -> new ArrayList<>()).add(pos);
            }
            lengths[d] = kept;
            for (Map.Entry<String, List<Integer>> e : local.entrySet()) {
                List<Integer> ps = e.getValue();
                int[] entry = new int[ps.size() + 1];
                entry[0] = d;
                for (int i = 0; i < ps.size(); i++) entry[i + 1] = ps.get(i);
                postings.computeIfAbsent(e.getKey(), t -> new ArrayList<>()).add(entry);
            }
        }
        String[] terms = postings.keySet().toArray(new String[0]);
        Arrays.sort(terms);
        int[][] pd = new int[terms.length][];
        int[][] pt = new int[terms.length][];
        int[][][] pp = new int[terms.length][][];
        for (int t = 0; t < terms.length; t++) {
            List<int[]> list = postings.get(terms[t]); // already in doc order: docs are visited in order
            pd[t] = new int[list.size()];
            pt[t] = new int[list.size()];
            pp[t] = new int[list.size()][];
            for (int i = 0; i < list.size(); i++) {
                int[] entry = list.get(i);
                pd[t][i] = entry[0];
                pt[t][i] = entry.length - 1;
                pp[t][i] = Arrays.copyOfRange(entry, 1, entry.length);
            }
        }
        return new InvertedIndex(terms, pd, pt, pp, lengths, ext, titles, analyzer.stems());
    }

    public int docCount() {
        return docLengths.length;
    }

    public int termCount() {
        return terms.length;
    }

    public long postingCount() {
        long c = 0;
        for (int[] p : postingDocs) c += p.length;
        return c;
    }

    public int docFreq(String term) {
        Integer id = termIds.get(term);
        return id == null ? 0 : postingDocs[id].length;
    }

    public double avgDocLength() {
        return avgDocLength;
    }

    public int externalId(int internal) {
        return externalIds[internal];
    }

    public String title(int internal) {
        return titles[internal];
    }
}
