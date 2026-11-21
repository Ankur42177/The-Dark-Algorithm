package com.pulse.assistant.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic 768-dim "embeddings" for tests: each word is hashed into a slot (a bag-of-words vector,
 * normalised). Texts sharing words get similar vectors — enough to test pgvector ranking without Ollama.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public volatile boolean down = false;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (down) {
            throw new IllegalStateException("embedding model down");
        }
        List<Embedding> out = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            out.add(new Embedding(vector(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return 768;
    }

    static float[] vector(String text) {
        float[] v = new float[768];
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("^search_(query|document): ", "").split("[^a-z0-9]+")) {
            if (w.length() > 2) {
                v[Math.floorMod(w.hashCode(), 768)] += 1f;
            }
        }
        double sum = 0;
        for (float f : v) {
            sum += f * f;
        }
        if (sum == 0) {
            v[0] = 1f;          // empty text: avoid an all-zero vector (cosine undefined)
            return v;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] /= (float) norm;
        }
        return v;
    }
}
