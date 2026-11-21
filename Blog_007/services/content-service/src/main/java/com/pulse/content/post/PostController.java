package com.pulse.content.post;

import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.PostDtos.PostDetail;
import com.pulse.content.post.PostDtos.PostSummary;
import com.pulse.content.post.PostDtos.SectionCount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.pulse.content.post.PostDtos.TrendingItem;
import com.pulse.content.views.ViewService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The WEB layer for the public, read-only API. Its only jobs:
 * read HTTP input -> validate it -> call the service -> return HTTP output.
 * No SQL and no business rules here.
 *
 * <p>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}: whatever a method returns
 * is written to the HTTP response as JSON (by Jackson).
 */
@RestController
@RequestMapping("/api/v1")   // every path below starts with /api/v1 (versioned API)
@Tag(name = "Public posts", description = "Read-only endpoints used by the website")
public class PostController {

    /** Browsers/CDNs may reuse a list response for 30 s; posts change rarely. Phase 6 adds instant purge. */
    private static final CacheControl LIST_CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();
    private static final CacheControl POST_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final PostService postService;
    private final ViewService viewService;

    public PostController(PostService postService, ViewService viewService) {
        this.postService = postService;
        this.viewService = viewService;
    }

    /**
     * GET /api/v1/posts?section=TECH&tag=kafka&cursor=...&limit=10
     *
     * <p>{@code @RequestParam(required = false)} = optional query parameter (null if missing).
     * {@code @Min/@Max} are Bean Validation rules; Spring validates them before the method body
     * runs and answers 400 Bad Request (as Problem Details JSON) if they fail.
     */
    @GetMapping("/posts")
    @Operation(summary = "List published posts, newest first (keyset pagination)")
    public ResponseEntity<CursorPage<PostSummary>> listPosts(
            @RequestParam(required = false) Section section,
            @RequestParam(required = false) @Pattern(regexp = "[a-zA-Z0-9-]{1,50}") String tag,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,200}") String cursor,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {

        CursorPage<PostSummary> page = postService.listPublished(Section.canonical(section), tag, cursor, limit);
        return ResponseEntity.ok().cacheControl(LIST_CACHE).body(page);
    }

    /** GET /api/v1/posts/kafka-consumer-groups-explained */
    @GetMapping("/posts/{slug}")
    @Operation(summary = "Get one published post by its slug")
    public ResponseEntity<PostDetail> getPost(
            @PathVariable @Pattern(regexp = "[a-z0-9-]{1,160}") String slug) {
        return ResponseEntity.ok().cacheControl(POST_CACHE).body(postService.getPublishedBySlug(slug));
    }

    /** GET /api/v1/sections -> [{"section":"AI","posts":3}, ...] */
    @GetMapping("/sections")
    @Operation(summary = "List sections with their number of published posts")
    public ResponseEntity<List<SectionCount>> sections() {
        return ResponseEntity.ok().cacheControl(LIST_CACHE).body(postService.sections());
    }

    /**
     * POST /api/v1/posts/{slug}/view — sent by navigator.sendBeacon from the article page (Phase 2).
     * 202 Accepted = "noted, processed asynchronously". No body, never cached.
     */
    @PostMapping("/posts/{slug}/view")
    @Operation(summary = "Count one view (fire-and-forget)")
    public ResponseEntity<Void> view(@PathVariable @Pattern(regexp = "[a-z0-9-]{1,160}") String slug) {
        viewService.record(slug);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** GET /api/v1/search?q=kafka+consumer&section=TECH&limit=10 — full-text search over published posts. */
    @GetMapping("/search")
    @Operation(summary = "Full-text search over published posts")
    public ResponseEntity<List<PostSummary>> search(
            @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String q,
            @RequestParam(required = false) Section section,
            @RequestParam(defaultValue = "10") @Min(1) @Max(25) int limit) {
        return ResponseEntity.ok().cacheControl(LIST_CACHE).body(postService.search(q, Section.canonical(section), limit));
    }

    /** GET /api/v1/trending?section=TECH&limit=5 — top posts by recent views (Redis sorted set). */
    @GetMapping("/trending")
    @Operation(summary = "Trending posts (by recent views)")
    public ResponseEntity<List<TrendingItem>> trending(
            @RequestParam(required = false) Section section,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(viewService.trending(Section.canonical(section), limit));
    }
}
