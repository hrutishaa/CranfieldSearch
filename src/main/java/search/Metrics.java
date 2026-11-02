package search;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Standard ranked-retrieval metrics. `ranked` is doc ids best-first; `grades` maps relevant doc -> grade (>0). */
public final class Metrics {
    private Metrics() {}

    public static double averagePrecision(List<Integer> ranked, Map<Integer, Integer> grades) {
        if (grades.isEmpty()) return 0;
        int hits = 0;
        double sum = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (grades.containsKey(ranked.get(i))) {
                hits++;
                sum += (double) hits / (i + 1);
            }
        }
        return sum / grades.size();
    }

    public static double precisionAt(List<Integer> ranked, Map<Integer, Integer> grades, int k) {
        int hits = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (grades.containsKey(ranked.get(i))) hits++;
        return (double) hits / k;
    }

    public static double recallAt(List<Integer> ranked, Map<Integer, Integer> grades, int k) {
        if (grades.isEmpty()) return 0;
        int hits = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (grades.containsKey(ranked.get(i))) hits++;
        return (double) hits / grades.size();
    }

    /** nDCG with exponential gain (2^grade - 1) and log2 discount. */
    public static double ndcgAt(List<Integer> ranked, Map<Integer, Integer> grades, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            int g = grades.getOrDefault(ranked.get(i), 0);
            dcg += (Math.pow(2, g) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        int[] ideal = grades.values().stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(ideal);
        double idcg = 0;
        for (int i = 0; i < Math.min(k, ideal.length); i++) {
            int g = ideal[ideal.length - 1 - i];
            idcg += (Math.pow(2, g) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }
}
