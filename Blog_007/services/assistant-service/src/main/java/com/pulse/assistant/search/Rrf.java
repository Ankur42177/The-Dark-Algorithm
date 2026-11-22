package com.pulse.assistant.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RECIPROCAL RANK FUSION — merge several ranked lists without comparing their scores.
 *
 * <p>Vector search gives cosine similarities (0.0–1.0); full-text search gives ts_rank values on a totally
 * different scale. RRF ignores the raw scores and only uses POSITIONS:
 * <pre>  score(doc) = Σ over lists  1 / (k + rank)      (rank starts at 1, k = 60)</pre>
 * A document ranked high in BOTH lists wins; a document found by only one list still gets in.
 */
public final class Rrf {

    public static final int K = 60;

    private Rrf() {
    }

    /** Fuse lists of items; {@code key} says when two items are "the same document". */
    @SafeVarargs
    public static <T> List<T> fuse(Function<T, String> key, int limit, List<T>... rankedLists) {
        Map<String, Double> score = new HashMap<>();
        Map<String, T> firstSeen = new LinkedHashMap<>();
        for (List<T> list : rankedLists) {
            for (int rank = 1; rank <= list.size(); rank++) {
                T item = list.get(rank - 1);
                String k = key.apply(item);
                score.merge(k, 1.0 / (K + rank), Double::sum);
                firstSeen.putIfAbsent(k, item);
            }
        }
        List<String> keys = new ArrayList<>(firstSeen.keySet());
        keys.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return keys.stream().limit(limit).map(firstSeen::get).toList();
    }
}
