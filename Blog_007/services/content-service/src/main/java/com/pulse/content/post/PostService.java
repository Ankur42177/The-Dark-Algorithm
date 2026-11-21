package com.pulse.content.post;

import com.pulse.content.cache.CacheKeys;
import com.pulse.content.cache.PostCache;
import com.pulse.content.common.PostNotFoundException;
import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.PostDtos.PostDetail;
import com.pulse.content.post.PostDtos.PostSummary;
import com.pulse.content.post.PostDtos.SectionCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;

/**
 * The SERVICE layer: business rules live here, between the web layer (controller) and the data
 * layer (repositories). The controller knows HTTP; the repositories know SQL; this class knows
 * "what a page of posts is".
 *
 * <p>Phase 3 added Redis cache-aside HERE — the controller and the SQL did not change at all.
 * That's the payoff of layering.
 */
@Service
public class PostService {

    /** Single posts change rarely: cache 1 h. Lists: 60 s (plus event-driven eviction in Phase 6). */
    static final Duration POST_TTL = Duration.ofHours(1);
    static final Duration LIST_TTL = Duration.ofSeconds(60);
    private static final TypeReference<CursorPage<PostSummary>> PAGE_TYPE = new TypeReference<>() { };
    private static final TypeReference<PostDetail> DETAIL_TYPE = new TypeReference<>() { };

    private final PostRepository postRepository;
    private final PostQueryRepository postQueryRepository;
    private final PostCache cache;

    public PostService(PostRepository postRepository, PostQueryRepository postQueryRepository, PostCache cache) {
        this.postRepository = postRepository;
        this.postQueryRepository = postQueryRepository;
        this.cache = cache;
    }

    /**
     * One page of published posts.
     *
     * <p>Trick: ask the database for {@code pageSize + 1} rows. If we get the extra row, there IS
     * a next page, and the cursor points at the last row we actually return. This avoids a second
     * {@code COUNT(*)} query, which gets slow on big tables.
     *
     * <p>Why no {@code @Transactional} here any more? A transaction borrows a DB connection for the whole
     * method — even on a Redis HIT, when we never touch Postgres. Without it, a cache hit uses zero DB
     * connections, and a miss borrows one only for the single query (Little's Law: shorter W ⇒ more capacity).
     */
    public CursorPage<PostSummary> listPublished(Section section, String tag, String cursorToken, int pageSize) {
        Cursor after = (cursorToken == null || cursorToken.isBlank()) ? null : Cursor.decode(cursorToken);
        String normalizedTag = (tag == null || tag.isBlank()) ? null : tag.trim().toLowerCase();
        String cursor = after == null ? null : cursorToken;
        // The generation number is part of the key: bumping it (on publish) makes all old list keys unreachable.
        String key = CacheKeys.list(cache.generation(CacheKeys.LIST_GENERATION), section, normalizedTag, cursor, pageSize);
        return cache.getOrLoad("list", key, LIST_TTL, PAGE_TYPE,
                () -> loadPage(section, normalizedTag, after, pageSize));
    }

    /** The original Phase 1 logic, now only run on a cache miss. */
    private CursorPage<PostSummary> loadPage(Section section, String normalizedTag, Cursor after, int pageSize) {

        List<PostSummary> rows = postQueryRepository.findPublished(section, normalizedTag, after, pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<PostSummary> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            PostSummary last = page.getLast();
            nextCursor = new Cursor(last.publishedAt(), last.id()).encode();
        }
        return new CursorPage<>(List.copyOf(page), nextCursor);
    }

    public PostDetail getPublishedBySlug(String slug) {
        return cache.getOrLoad("post", CacheKeys.post(slug), POST_TTL, DETAIL_TYPE, () ->
                postRepository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                        .map(PostDetail::from)                            // Optional<Post> -> Optional<PostDetail>
                        .orElseThrow(() -> new PostNotFoundException(slug))); // empty -> 404 (not cached)
    }

    /** Called when a post changes (admin in Phase 4, Kafka consumer in Phase 6). */
    public void evict(String slug) {
        cache.evict(CacheKeys.post(slug));
        cache.bumpGeneration(CacheKeys.LIST_GENERATION);
    }

    @Transactional(readOnly = true)
    public List<SectionCount> sections() {
        return postQueryRepository.countPublishedBySection();
    }
}
