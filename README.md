# CranfieldSearch

A small search engine written from scratch in Java: tokenizer, Porter stemmer, positional inverted index, BM25 ranking, phrase queries, prefix autocomplete and a compact on-disk index format. No Lucene, no libraries outside the JDK (JUnit for tests).

I built it on the Cranfield collection because it comes with human relevance judgments, so "does the ranking work?" has an actual answer instead of me eyeballing results.

**Live demo:** https://hrutishaa.github.io/CranfieldSearch/ (the Java tool exports its index to JSON and the page ranks with the same BM25 formula in the browser)

## Data

The **Cranfield collection** (Cyril Cleverdon, Cranfield Institute of Technology, 1960s), downloaded from the University of Glasgow IR test collections page: [ir.dcs.gla.ac.uk/resources/test_collections/cran](https://ir.dcs.gla.ac.uk/resources/test_collections/cran/) (`cran.tar.gz`). It's committed unchanged in `data/`:

- `cran.all.1400`: 1,400 aerodynamics paper abstracts (title, author, source, abstract)
- `cran.qry`: 225 natural-language queries ("what problems of heat conduction in composite slabs have been solved so far")
- `cranqrel`: 1,836 query/document judgments with a relevance code

The Porter stemmer test uses Martin Porter's own 23,531-word sample vocabulary and expected output from [tartarus.org/martin/PorterStemmer](https://tartarus.org/martin/PorterStemmer/), stored in `src/test/resources`.

Two things about the data that bit me:

1. **Query ids don't line up.** `cran.qry` numbers queries 001 to 365 with gaps, but `cranqrel` numbers them 1 to 225 by position. Using the file ids directly gives every query the wrong judgments and the scores look plausible but meaningless. `Corpus.readQueries` renumbers them and keeps the original id.
2. **Relevance codes run backwards.** Code 1 is "complete answer" and 4 is "minimal interest", so I map them to grades 4 to 1 for nDCG. There are also 225 rows with code -1, which the readme doesn't define; I treat them as not relevant. That leaves 1,612 relevant judgments.

## How it works

```
text -> tokenize (letters/digits) -> lowercase -> stopwords -> Porter stem
     -> positional postings: term -> [doc ids], [term freqs], [positions per doc]
query -> same analyzer -> term-at-a-time BM25 into a score array -> size-k min-heap -> top k
"quoted phrase" -> positional intersection, driven from the rarest term
```

A few decisions worth explaining:

- **Positions count raw tokens, not kept terms.** If stopwords were squeezed out first, `"boundary of the layer"` would match `boundary layer`. Keeping the gap means a phrase only matches what was actually written.
- **Top-k with a min-heap** instead of sorting every matching doc. Ties break toward the lower doc id so results are deterministic, which matters because CI checks the evaluation output byte for byte.
- **Index format** stores doc ids and positions as gaps written as variable-length ints (7 bits per byte). The positional index for all 1,400 docs is 510,520 bytes, about a third of the 1,548,451 bytes of text it indexes.
- **Thread safety:** the index is immutable after build and `Searcher` keeps all scoring state per call, so one instance serves any number of threads without locks.
- I did not tune the stopword list or BM25 parameters on these queries. The stopwords are a generic English list and BM25 uses the textbook k1 = 1.2, b = 0.75, so the numbers below aren't fitted to the test set.

## Results

All 225 queries, top 1,000 results each, from `java -jar target/cranfield-search-1.0.jar eval` (saved to `docs/results.json`):

| Configuration | MAP | P@10 | nDCG@10 | Recall@100 |
|---|---|---|---|---|
| TF-IDF, no stemming | 0.2952 | 0.2289 | 0.3423 | 0.7216 |
| BM25, no stemming | 0.2937 | 0.2351 | 0.3520 | 0.7176 |
| BM25 + Porter stemming | **0.3158** | **0.2369** | **0.3596** | **0.7499** |

BM25 alone barely moves MAP over a length-normalized TF-IDF here, but it does improve the top of the ranking (nDCG@10 0.342 to 0.352). Stemming is the bigger win: MAP goes up 7.5% (0.2937 to 0.3158) and Recall@100 from 71.8% to 75.0%.

Speed, from `bench` on an 8-core Linux box with Java 17 (`docs/bench.json`, will vary by machine):

- Build the full index: 56.7 ms (median of 7). Load it back from disk: 8.4 ms.
- Query latency over 9,000 timed queries (225 queries x 40 rounds, top 10): p50 28 µs, p95 46 µs, p99 93 µs.
- Throughput with a shared `Searcher`: 33,521 queries/s on 1 thread, 241,892 on 8 threads (7.2x).

## Run it

```
mvn package                                         # builds and runs the 20 tests
java -jar target/cranfield-search-1.0.jar search 'heat transfer in hypersonic flow'
java -jar target/cranfield-search-1.0.jar search '"boundary layer" transition' 5
java -jar target/cranfield-search-1.0.jar suggest aero
java -jar target/cranfield-search-1.0.jar eval      # writes docs/results.json
java -jar target/cranfield-search-1.0.jar bench     # writes docs/bench.json
java -jar target/cranfield-search-1.0.jar export-web
```

Java 17, Maven, JUnit 5. CI builds, runs the tests, re-runs the evaluation and fails if `docs/results.json` or the demo index drift from what's committed.

## Tests

- The stemmer matches Porter's reference output on all 23,531 words of his sample vocabulary.
- A BM25 score is checked against the formula worked out by hand on a 4-document corpus.
- Phrase queries, stopwords inside phrases, top-k cutoff and tie-breaking, and the mismatched-analyzer guard.
- The full Cranfield index is written, read back and compared array by array, and gives identical results after the round trip.
- Varint encoding at the byte boundaries (127/128, 16383/16384, Integer.MAX_VALUE).
- Metric math (AP, P@10, nDCG, recall) on a ranking small enough to check by hand.

## Limitations

- The whole index lives in memory. That's fine for 1,400 docs; for millions you'd want memory-mapped, block-compressed postings and skip lists.
- No query expansion or relevance feedback, which is where most of the remaining Cranfield gains are in the literature.
- The browser demo has no positions in its JSON, so it checks phrases against the document text instead of the postings. Same results, different mechanism.

## Layout

```
data/                        Cranfield files, unchanged
src/main/java/search/
  Analyzer, PorterStemmer    text -> terms
  InvertedIndex              positional postings in plain arrays
  Scorer, Searcher           BM25 / TF-IDF, top-k, phrases
  IndexIO                    varint gap-encoded index file
  PrefixTrie                 autocomplete
  Metrics, Corpus, Main      evaluation, parsing, CLI
docs/                        results, benchmark, GitHub Pages demo
```
