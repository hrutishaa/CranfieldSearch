package search;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;

/** Autocomplete over the corpus vocabulary: walk to the prefix node, then keep the k most frequent words below it. */
public final class PrefixTrie {
    private static final class Node {
        final TreeMap<Character, Node> kids = new TreeMap<>();
        int count; // > 0 only where a word ends
    }

    public record Suggestion(String word, int count) {}

    private final Node root = new Node();
    private int words;

    public void add(String word, int count) {
        Node n = root;
        for (int i = 0; i < word.length(); i++) n = n.kids.computeIfAbsent(word.charAt(i), c -> new Node());
        if (n.count == 0) words++;
        n.count += count;
    }

    public int size() {
        return words;
    }

    public List<Suggestion> suggest(String prefix, int k) {
        Node n = root;
        for (int i = 0; i < prefix.length() && n != null; i++) n = n.kids.get(prefix.charAt(i));
        if (n == null || k <= 0) return List.of();
        // min-heap on (count, then reverse alphabetical) so ties come out alphabetically
        PriorityQueue<Suggestion> heap = new PriorityQueue<>((a, b) -> a.count() != b.count()
            ? Integer.compare(a.count(), b.count()) : b.word().compareTo(a.word()));
        collect(n, new StringBuilder(prefix), heap, k);
        List<Suggestion> out = new ArrayList<>(heap);
        out.sort((a, b) -> a.count() != b.count() ? Integer.compare(b.count(), a.count()) : a.word().compareTo(b.word()));
        return out;
    }

    private void collect(Node n, StringBuilder sb, PriorityQueue<Suggestion> heap, int k) {
        if (n.count > 0) {
            heap.add(new Suggestion(sb.toString(), n.count));
            if (heap.size() > k) heap.poll();
        }
        for (var e : n.kids.entrySet()) {
            sb.append(e.getKey());
            collect(e.getValue(), sb, heap, k);
            sb.setLength(sb.length() - 1);
        }
    }
}
