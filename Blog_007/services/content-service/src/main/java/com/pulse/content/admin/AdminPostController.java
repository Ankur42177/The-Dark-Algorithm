package com.pulse.content.admin;

import com.pulse.content.admin.AdminDtos.AdminPostRow;
import com.pulse.content.admin.AdminDtos.AdminPostView;
import com.pulse.content.admin.AdminDtos.PostRequest;
import com.pulse.content.admin.AdminDtos.PreviewRequest;
import com.pulse.content.admin.AdminDtos.PreviewResponse;
import com.pulse.content.admin.AdminDtos.ScheduleRequest;
import com.pulse.content.cache.PostCache;
import com.pulse.content.markdown.MarkdownRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Admin API (requires ROLE_ADMIN via JWT + a CSRF token for writes — see SecurityConfig).
 * Responses are {@code Cache-Control: no-store}: never cache private data anywhere.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin posts")
public class AdminPostController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final AdminPostService service;
    private final MarkdownRenderer markdown;
    private final StringRedisTemplate redis;
    private final PostCache breaker;

    public AdminPostController(AdminPostService service, MarkdownRenderer markdown, StringRedisTemplate redis, PostCache breaker) {
        this.service = service;
        this.markdown = markdown;
        this.redis = redis;
        this.breaker = breaker;
    }

    @GetMapping("/posts")
    public ResponseEntity<List<AdminPostRow>> list() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.list());
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<AdminPostView> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.get(id));
    }

    /**
     * Create a draft. IDEMPOTENCY: if the browser retries the same request (double click, flaky network)
     * with the same {@code Idempotency-Key}, we return the post created the first time instead of a duplicate.
     */
    @PostMapping("/posts")
    @Operation(summary = "Create a draft (supports Idempotency-Key)")
    public ResponseEntity<AdminPostView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idemKey,
            @Valid @RequestBody PostRequest req) {
        if (idemKey == null || !breaker.redisUsable()) {
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(service.create(req));
        }
        String key = "idem:" + idemKey;
        Boolean first = redis.opsForValue().setIfAbsent(key, "pending", Duration.ofHours(24)); // claim the key atomically
        if (!Boolean.TRUE.equals(first)) {
            String existing = redis.opsForValue().get(key);
            if (existing == null || "pending".equals(existing)) {
                throw new AdminPostService.Conflict("A request with this Idempotency-Key is still in progress");
            }
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").cacheControl(NO_STORE)
                    .body(service.get(UUID.fromString(existing)));
        }
        try {
            AdminPostView created = service.create(req);
            redis.opsForValue().set(key, created.id().toString(), Duration.ofHours(24));
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(created);
        } catch (RuntimeException e) {
            redis.delete(key);              // failed: allow a retry with the same key
            throw e;
        }
    }

    @PutMapping("/posts/{id}")
    @Operation(summary = "Edit (send the version you loaded; 409 if someone else saved first)")
    public ResponseEntity<AdminPostView> update(@PathVariable UUID id, @Valid @RequestBody PostRequest req) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.update(id, req));
    }

    @PostMapping("/posts/{id}/publish")
    public ResponseEntity<AdminPostView> publish(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.publish(id));
    }

    @PostMapping("/posts/{id}/schedule")
    public ResponseEntity<AdminPostView> schedule(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest req) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.schedule(id, req.publishAt()));
    }

    @PostMapping("/posts/{id}/unpublish")
    public ResponseEntity<AdminPostView> unpublish(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.unpublish(id));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Live preview for the editor: exactly the HTML readers will get (rendered + sanitized). */
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@Valid @RequestBody PreviewRequest req) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(new PreviewResponse(markdown.toSafeHtml(req.bodyMd()), MarkdownRenderer.readingMinutes(req.bodyMd())));
    }
}
