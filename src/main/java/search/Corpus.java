package search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the Cranfield collection in its original SMART format (.I/.T/.A/.B/.W fields). */
public final class Corpus {
    public record Doc(int id, String title, String author, String text) {
        /** What gets indexed: title + abstract. Author and bibliography lines are left out. */
        public String body() {
            return title + " " + text;
        }
    }

    public record Query(int id, int originalId, String text) {}

    private Corpus() {}

    public static List<Doc> readDocs(Path file) throws IOException {
        List<Doc> docs = new ArrayList<>();
        for (Map<String, String> rec : readRecords(file)) {
            docs.add(new Doc(Integer.parseInt(rec.get("I")), rec.getOrDefault("T", ""),
                rec.getOrDefault("A", ""), rec.getOrDefault("W", "")));
        }
        return docs;
    }

    /**
     * Query ids in cran.qry run 001..365 with gaps, but cranqrel numbers queries 1..225 by position.
     * Using the file's ids directly silently scores every query against the wrong judgments,
     * so queries are renumbered here and the original id is kept for reference.
     */
    public static List<Query> readQueries(Path file) throws IOException {
        List<Query> out = new ArrayList<>();
        int n = 0;
        for (Map<String, String> rec : readRecords(file)) {
            out.add(new Query(++n, Integer.parseInt(rec.get("I")), rec.getOrDefault("W", "")));
        }
        return out;
    }

    /**
     * query id -> (doc id -> grade). Cranfield codes 1 (complete answer) .. 4 (minimal interest)
     * become grades 4..1. Code -1 has no documented meaning and is treated as not relevant.
     */
    public static Map<Integer, Map<Integer, Integer>> readQrels(Path file) throws IOException {
        Map<Integer, Map<Integer, Integer>> qrels = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) continue;
            int q = Integer.parseInt(p[0]), d = Integer.parseInt(p[1]), code = Integer.parseInt(p[2]);
            if (code < 1 || code > 4) continue;
            qrels.computeIfAbsent(q, x -> new HashMap<>()).put(d, 5 - code);
        }
        return qrels;
    }

    private static List<Map<String, String>> readRecords(Path file) throws IOException {
        List<Map<String, String>> recs = new ArrayList<>();
        Map<String, String> cur = null;
        String field = null;
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith(".I ")) {
                if (cur != null && field != null) cur.put(field, sb.toString().trim());
                cur = new HashMap<>();
                cur.put("I", line.substring(3).trim());
                recs.add(cur);
                field = null;
                sb.setLength(0);
            } else if (line.length() == 2 && line.charAt(0) == '.' && cur != null) {
                if (field != null) cur.put(field, sb.toString().trim());
                field = line.substring(1);
                sb.setLength(0);
            } else if (field != null) {
                sb.append(line).append(' ');
            }
        }
        if (cur != null && field != null) cur.put(field, sb.toString().trim());
        return recs;
    }
}
