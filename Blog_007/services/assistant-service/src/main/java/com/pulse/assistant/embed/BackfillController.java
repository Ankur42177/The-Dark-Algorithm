package com.pulse.assistant.embed;

import com.pulse.assistant.search.VectorStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * INTERNAL endpoints (/internal/** is never routed by the api-gateway): rebuild the vector index from
 * content-service. Useful before Kafka exists, after an outage, or when switching embedding models.
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
    @Operation(summary = "Embed every published post (internal)")
    public Map<String, Object> backfill() {
        long start = System.currentTimeMillis();
        int chunks = embedder.backfill();
        return Map.of("chunksWritten", chunks, "totalChunks", vectors.countChunks(), "ms", System.currentTimeMillis() - start);
    }

    @GetMapping("/internal/v1/embed/stats")
    public Map<String, Object> stats() {
        return Map.of("totalChunks", vectors.countChunks());
    }
}
