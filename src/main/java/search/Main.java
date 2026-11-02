package search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Command line entry point.
 *   search "query text" [k]    ranked results (BM25 + Porter stemming)
 *   suggest prefix [k]         autocomplete from the corpus vocabulary
 *   eval                       MAP / P@10 / nDCG@10 for 225 Cranfield queries -> docs/results.json
 *   bench                      build, load and query latency -> docs/bench.json
 *   export-web                 compact JSON index for the GitHub Pages demo
 */
public final class Main {
    static final Path DATA = Path.of("data");
    static final Path DOCS = Path.of("docs");

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String cmd = args.length == 0 ? "eval" : args[0];
        switch (cmd) {
            case "search" -> search(args);
            case "suggest" -> suggest(args);
            case "eval" -> eval();
            case "bench" -> bench();
            case "export-web" -> exportWeb();
            default -> {
                System.err.println("usage: search <query> [k] | suggest <prefix> [k] | eval | bench | export-web");
                System.exit(2);
            }
        }
    }

    static List<Corpus.Doc> docs() throws IOException {
        return Corpus.readDocs(DATA.resolve("cran.all.1400"));
    }

    static void search(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("search needs a query");
        int k = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        Analyzer a = Analyzer.standard();
        Searcher s = new Searcher(InvertedIndex.build(docs(), a), a);
        int rank = 0;
        for (Searcher.Hit h : s.search(args[1], k, Scorer.bm25())) {
            System.out.printf("%2d. [%4d] %.3f  %s%n", ++rank, h.docId(), h.score(), h.title());
        }
        if (rank == 0) System.out.println("no matches");
    }

    static PrefixTrie vocabularyTrie(List<Corpus.Doc> docs) {
        Map<String, Integer> counts = new HashMap<>();
        for (Corpus.Doc d : docs) for (String t : Analyzer.tokenize(d.body())) {
            if (t.length() > 2 && !Analyzer.STOPWORDS.contains(t) && Character.isLetter(t.charAt(0))) counts.merge(t, 1, Integer::sum);
        }
        PrefixTrie trie = new PrefixTrie();
        counts.forEach(trie::add);
        return trie;
    }

    static void suggest(String[] args) throws IOException {
        int k = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        for (PrefixTrie.Suggestion s : vocabularyTrie(docs()).suggest(args[1].toLowerCase(), k)) {
            System.out.printf("%-24s %d%n", s.word(), s.count());
        }
    }

    record Run(String name, Analyzer analyzer, Scorer scorer) {}

    static List<Run> runs() {
        return List.of(
            new Run("tfidf", new Analyzer(false, true), Scorer.tfIdf()),
            new Run("bm25", new Analyzer(false, true), Scorer.bm25()),
            new Run("bm25+porter", Analyzer.standard(), Scorer.bm25()));
    }

    static Map<String, Double> evaluate(InvertedIndex idx, Run run, List<Corpus.Query> queries,
                                        Map<Integer, Map<Integer, Integer>> qrels) {
        Searcher s = new Searcher(idx, run.analyzer());
        double map = 0, p10 = 0, ndcg = 0, r100 = 0;
        int judged = 0;
        for (Corpus.Query q : queries) {
            Map<Integer, Integer> g = qrels.getOrDefault(q.id(), Map.of());
            if (g.isEmpty()) continue;
            judged++;
            List<Integer> ranked = s.search(q.text(), 1000, run.scorer()).stream().map(Searcher.Hit::docId).toList();
            map += Metrics.averagePrecision(ranked, g);
            p10 += Metrics.precisionAt(ranked, g, 10);
            ndcg += Metrics.ndcgAt(ranked, g, 10);
            r100 += Metrics.recallAt(ranked, g, 100);
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("queries", (double) judged);
        m.put("map", round(map / judged));
        m.put("p10", round(p10 / judged));
        m.put("ndcg10", round(ndcg / judged));
        m.put("recall100", round(r100 / judged));
        return m;
    }

    static void eval() throws IOException {
        List<Corpus.Doc> docs = docs();
        List<Corpus.Query> queries = Corpus.readQueries(DATA.resolve("cran.qry"));
        Map<Integer, Map<Integer, Integer>> qrels = Corpus.readQrels(DATA.resolve("cranqrel"));
        StringBuilder json = new StringBuilder("{\n");
        InvertedIndex stemmed = InvertedIndex.build(docs, Analyzer.standard());
        long judgments = qrels.values().stream().mapToLong(Map::size).sum();
        json.append(String.format("  \"collection\": {\"docs\": %d, \"queries\": %d, \"relevantJudgments\": %d},%n",
            docs.size(), queries.size(), judgments));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IndexIO.write(stemmed, bytes);
        long raw = 0;
        for (Corpus.Doc d : docs) raw += d.body().getBytes(StandardCharsets.UTF_8).length;
        json.append(String.format("  \"index\": {\"terms\": %d, \"postings\": %d, \"avgDocLength\": %.2f, \"indexedTextBytes\": %d, \"indexBytes\": %d},%n",
            stemmed.termCount(), stemmed.postingCount(), stemmed.avgDocLength(), raw, bytes.size()));
        json.append("  \"runs\": {\n");
        List<Run> runs = runs();
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            InvertedIndex idx = run.analyzer().stems() ? stemmed : InvertedIndex.build(docs, run.analyzer());
            Map<String, Double> m = evaluate(idx, run, queries, qrels);
            System.out.printf("%-12s MAP %.4f  P@10 %.4f  nDCG@10 %.4f  R@100 %.4f%n",
                run.name(), m.get("map"), m.get("p10"), m.get("ndcg10"), m.get("recall100"));
            json.append(String.format("    \"%s\": {\"scorer\": \"%s\", \"stemming\": %b, \"map\": %.4f, \"p10\": %.4f, \"ndcg10\": %.4f, \"recall100\": %.4f}%s%n",
                run.name(), run.scorer().name(), run.analyzer().stems(), m.get("map"), m.get("p10"), m.get("ndcg10"), m.get("recall100"),
                i < runs.size() - 1 ? "," : ""));
        }
        json.append("  }\n}\n");
        Files.createDirectories(DOCS);
        Files.writeString(DOCS.resolve("results.json"), json.toString());
        System.out.println("wrote docs/results.json");
    }

    static void bench() throws Exception {
        List<Corpus.Doc> docs = docs();
        List<Corpus.Query> queries = Corpus.readQueries(DATA.resolve("cran.qry"));
        Analyzer a = Analyzer.standard();
        for (int i = 0; i < 3; i++) InvertedIndex.build(docs, a); // warm up
        long[] build = new long[7];
        InvertedIndex idx = null;
        for (int i = 0; i < build.length; i++) {
            long t = System.nanoTime();
            idx = InvertedIndex.build(docs, a);
            build[i] = System.nanoTime() - t;
        }
        Path tmp = Files.createTempFile("cran", ".bin");
        IndexIO.write(idx, tmp);
        long[] load = new long[7];
        for (int i = 0; i < 3; i++) IndexIO.read(tmp);
        for (int i = 0; i < load.length; i++) {
            long t = System.nanoTime();
            IndexIO.read(tmp);
            load[i] = System.nanoTime() - t;
        }
        long indexBytes = Files.size(tmp);
        Files.delete(tmp);

        Searcher s = new Searcher(idx, a);
        Scorer bm25 = Scorer.bm25();
        for (int r = 0; r < 20; r++) for (Corpus.Query q : queries) s.search(q.text(), 10, bm25);
        int rounds = 40;
        long[] lat = new long[rounds * queries.size()];
        int n = 0;
        for (int r = 0; r < rounds; r++) {
            for (Corpus.Query q : queries) {
                long t = System.nanoTime();
                s.search(q.text(), 10, bm25);
                lat[n++] = System.nanoTime() - t;
            }
        }
        Arrays.sort(lat);

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<Integer, Double> qps = new TreeMap<>();
        throughput(s, queries, threads, bm25); // warm every thread count's code path before timing
        for (int th : new int[] {1, 2, 4, threads}) {
            if (qps.containsKey(th) || th > threads) continue;
            qps.put(th, throughput(s, queries, th, bm25));
        }

        StringBuilder json = new StringBuilder("{\n");
        json.append(String.format("  \"cpus\": %d,%n  \"java\": \"%s\",%n", threads, System.getProperty("java.version")));
        json.append(String.format("  \"buildMsMedian\": %.1f,%n  \"loadMsMedian\": %.1f,%n  \"indexBytes\": %d,%n",
            median(build) / 1e6, median(load) / 1e6, indexBytes));
        json.append(String.format("  \"queryLatencyMicros\": {\"samples\": %d, \"p50\": %.0f, \"p95\": %.0f, \"p99\": %.0f},%n",
            lat.length, lat[lat.length / 2] / 1e3, lat[(int) (lat.length * 0.95)] / 1e3, lat[(int) (lat.length * 0.99)] / 1e3));
        json.append("  \"queriesPerSecond\": {");
        int i = 0;
        for (var e : qps.entrySet()) {
            json.append(String.format("%s\"%d\": %.0f", i++ == 0 ? "" : ", ", e.getKey(), e.getValue()));
        }
        json.append("}\n}\n");
        System.out.print(json);
        Files.createDirectories(DOCS);
        Files.writeString(DOCS.resolve("bench.json"), json.toString());
    }

    static double throughput(Searcher s, List<Corpus.Query> queries, int threads, Scorer scorer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int perThread = 30; // passes over all 225 queries per thread
            runPasses(pool, s, queries, threads, 10, scorer);
            long t = System.nanoTime();
            runPasses(pool, s, queries, threads, perThread, scorer);
            double secs = (System.nanoTime() - t) / 1e9;
            return (double) threads * perThread * queries.size() / secs;
        } finally {
            pool.shutdown();
        }
    }

    private static void runPasses(ExecutorService pool, Searcher s, List<Corpus.Query> queries, int threads,
                                  int passes, Scorer scorer) throws Exception {
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                for (int p = 0; p < passes; p++) for (Corpus.Query q : queries) s.search(q.text(), 10, scorer);
            }));
        }
        for (Future<?> f : fs) f.get();
    }

    static void exportWeb() throws IOException {
        List<Corpus.Doc> docs = docs();
        Analyzer a = Analyzer.standard();
        InvertedIndex idx = InvertedIndex.build(docs, a);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"n\":").append(idx.docCount()).append(",\"avgdl\":").append(String.format("%.4f", idx.avgDocLength()));
        sb.append(",\"docs\":[");
        for (int d = 0; d < idx.docCount(); d++) {
            if (d > 0) sb.append(',');
            sb.append('[').append(idx.externalIds[d]).append(',').append(idx.docLengths[d]).append(',')
                .append(jsonString(idx.titles[d])).append(',').append(jsonString(docs.get(d).body())).append(']');
        }
        sb.append("],\"postings\":{");
        for (int t = 0; t < idx.termCount(); t++) {
            if (t > 0) sb.append(',');
            sb.append(jsonString(idx.terms[t])).append(":[");
            for (int i = 0; i < idx.postingDocs[t].length; i++) {
                if (i > 0) sb.append(',');
                sb.append(idx.postingDocs[t][i]).append(',').append(idx.postingTfs[t][i]);
            }
            sb.append(']');
        }
        // raw word -> stem for every word in the corpus, so the page doesn't need its own stemmer
        Map<String, String> stems = new TreeMap<>();
        for (Corpus.Doc d : docs) for (String t : Analyzer.tokenize(d.body())) {
            String s = a.normalize(t);
            if (s != null) stems.put(t, s);
        }
        sb.append("},\"stems\":{");
        int i = 0;
        for (var e : stems.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
        }
        sb.append("},\"suggest\":[");
        List<PrefixTrie.Suggestion> top = vocabularyTrie(docs).suggest("", 5000);
        for (int j = 0; j < top.size(); j++) {
            if (j > 0) sb.append(',');
            sb.append(jsonString(top.get(j).word()));
        }
        sb.append("]}");
        Files.createDirectories(DOCS);
        Files.writeString(DOCS.resolve("web-index.json"), sb.toString());
        System.out.println("wrote docs/web-index.json (" + sb.length() / 1024 + " KB)");
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static double median(long[] xs) {
        long[] c = xs.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
