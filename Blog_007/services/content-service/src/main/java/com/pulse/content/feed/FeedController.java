package com.pulse.content.feed;

import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.Section;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** GET /api/v1/feed?section=&source=&cursor=&limit= — curated external articles, metadata + link out only. */
@RestController
@RequestMapping("/api/v1")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Around the web")
public class FeedController {

    private final FeedService feed;

    public FeedController(FeedService feed) {
        this.feed = feed;
    }

    @GetMapping("/feed")
    @Operation(summary = "External articles (metadata + link), newest first, keyset pagination")
    public ResponseEntity<CursorPage<FeedItem>> feed(@RequestParam(required = false) Section section,
                                                     @RequestParam(required = false) @Size(max = 120) String source,
                                                     @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,200}") String cursor,
                                                     @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(feed.page(Section.canonical(section), source, cursor, limit));
    }
}
