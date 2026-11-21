package com.pulse.content.post;

import com.pulse.content.post.PostDtos.PostDetail;
import com.pulse.content.post.PostDtos.PostSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * INTERNAL API (PLAN §12.3): service-to-service only. The api-gateway never routes /internal/**, so
 * browsers can't reach it; inside Docker/Kubernetes, other services call http://content-service:8081/internal/...
 * Separate from the public API so it can change shape for its callers (the AI assistant's tools)
 * without breaking the website.
 */
@RestController
@RequestMapping("/internal/v1")
@Tag(name = "Internal", description = "Service-to-service endpoints (not exposed by the gateway)")
public class InternalPostController {

    private final PostService postService;

    public InternalPostController(PostService postService) {
        this.postService = postService;
    }

    /** GET /internal/v1/posts?section=AI&sinceDays=7&limit=10 */
    @GetMapping("/posts")
    @Operation(summary = "Recent published posts (for assistant tools)")
    public List<PostSummary> recent(@RequestParam(required = false) Section section,
                                    @RequestParam(defaultValue = "30") @Min(1) @Max(3650) int sinceDays,
                                    @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return postService.recent(section, sinceDays, limit);
    }

    /** GET /internal/v1/posts/{slug} */
    @GetMapping("/posts/{slug}")
    public PostDetail post(@PathVariable @Pattern(regexp = "[a-z0-9-]{1,160}") String slug) {
        return postService.getPublishedBySlug(slug);
    }
}
