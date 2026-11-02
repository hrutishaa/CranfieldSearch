package search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Lowercases, splits on anything that isn't a letter or digit, drops stopwords and optionally stems. */
public final class Analyzer {
    // A generic English stopword list (the short NLTK-style one). I deliberately did not add
    // Cranfield-specific words like "problem" or "results": that would be tuning on the test set.
    static final Set<String> STOPWORDS = Set.of(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "below", "between", "both", "but", "by", "can", "could",
        "did", "do", "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has",
        "have", "having", "he", "her", "here", "hers", "him", "his", "how", "i", "if", "in", "into", "is", "it",
        "its", "itself", "just", "may", "me", "might", "more", "most", "must", "my", "no", "nor", "not", "of",
        "off", "on", "once", "only", "or", "other", "our", "ours", "out", "over", "own", "same", "she", "should",
        "so", "some", "such", "than", "that", "the", "their", "theirs", "them", "then", "there", "these", "they",
        "this", "those", "through", "to", "too", "under", "until", "up", "very", "was", "we", "were", "what",
        "when", "where", "which", "while", "who", "whom", "why", "will", "with", "would", "you", "your", "yours");

    private final boolean stem;
    private final boolean dropStopwords;
    private final ThreadLocal<PorterStemmer> stemmer = ThreadLocal.withInitial(PorterStemmer::new);

    public Analyzer(boolean stem, boolean dropStopwords) {
        this.stem = stem;
        this.dropStopwords = dropStopwords;
    }

    public static Analyzer standard() {
        return new Analyzer(true, true);
    }

    /** Raw tokens in order, lowercased, before stopwords and stemming. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean word = i < text.length() && Character.isLetterOrDigit(text.charAt(i));
            if (word && start < 0) start = i;
            else if (!word && start >= 0) {
                out.add(text.substring(start, i).toLowerCase());
                start = -1;
            }
        }
        return out;
    }

    /** Analyzed terms in order. Positions are kept by the caller so phrase queries still line up. */
    public List<String> analyze(String text) {
        List<String> out = new ArrayList<>();
        for (String t : tokenize(text)) {
            String term = normalize(t);
            if (term != null) out.add(term);
        }
        return out;
    }

    /** Returns the indexed form of one raw token, or null if it should be dropped. */
    public String normalize(String token) {
        if (token.length() < 2 && !Character.isDigit(token.charAt(0))) return null;
        if (dropStopwords && STOPWORDS.contains(token)) return null;
        return stem ? stemmer.get().stem(token) : token;
    }

    public boolean stems() {
        return stem;
    }
}
