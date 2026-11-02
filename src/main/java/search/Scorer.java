package search;

/** Ranking function: an idf part per term and a tf part per (term, doc). */
public interface Scorer {
    double idf(int docFreq, int docCount);

    double tf(int tf, int docLength, double avgDocLength);

    String name();

    /** Okapi BM25 with the Lucene-style idf that never goes negative. */
    static Scorer bm25(double k1, double b) {
        return new Scorer() {
            public double idf(int df, int n) {
                return Math.log(1 + (n - df + 0.5) / (df + 0.5));
            }

            public double tf(int tf, int dl, double avgdl) {
                return tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avgdl));
            }

            public String name() {
                return "BM25(k1=" + k1 + ",b=" + b + ")";
            }
        };
    }

    static Scorer bm25() {
        return bm25(1.2, 0.75);
    }

    /** Log-scaled tf times idf, divided by sqrt(doc length) so long abstracts don't win by default. */
    static Scorer tfIdf() {
        return new Scorer() {
            public double idf(int df, int n) {
                return Math.log((double) n / df);
            }

            public double tf(int tf, int dl, double avgdl) {
                return (1 + Math.log(tf)) / Math.sqrt(dl);
            }

            public String name() {
                return "TF-IDF";
            }
        };
    }
}
