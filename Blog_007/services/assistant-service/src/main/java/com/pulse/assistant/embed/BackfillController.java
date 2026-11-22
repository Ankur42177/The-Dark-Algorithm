package com.pulse.assistant.embed;

import com.pulse.assistant.search.VectorStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INTERNAL endpoints (/internal/** is never routed by the api-gateway): inspect and repair the vector index.
 * <pre>
 *   POST /internal/v1/embed/backfill            reconcile: embed missing/changed docs, delete gone ones (hash-skips unchanged)
 *   POST /internal/v1/embed/backfill?force=true re-embed EVERYTHING (e.g. after switching embedding models)
 *   GET  /internal/v1/embed/stats               totals, per type/section/source, and the last reconcile report
 * </pre>
 */
@RestController
public class BackfillController {

    private final EmbedderService embedder;
    private final VectorStore vectors;

    public BackfillController(EmbedderService embedder, VectorStore vectors) {
        this.embedder = embedder;
        this.vectors = vectors;
    }

    @PostMapping("/internal/v1/embed/backfill")
    @Operation(summary = "Reconcile the vector index with the site (internal)")
    public EmbedderService.ReconcileReport backfill(@RequestParam(defaultValue = "false") boolean force) {
        return embedder.reconcile(force);
    }

    @GetMapping("/internal/v1/embed/stats")
    @Operation(summary = "Vector index statistics (internal)")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalChunks", vectors.countChunks());
        m.put("documents", vectors.documentHashes("POST").size() + vectors.documentHashes("ARTICLE").size());
        m.put("breakdown", vectors.breakdown());
        m.put("lastReconcile", embedder.lastReport());
        return m;
    }
}
