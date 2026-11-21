package com.pulse.content.post;

import com.pulse.content.common.PostNotFoundException;
import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.PostDtos.PostDetail;
import com.pulse.content.post.PostDtos.PostSummary;
import com.pulse.content.post.PostDtos.SectionCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The SERVICE layer: business rules live here, between the web layer (controller) and the data
 * layer (repositories). The controller knows HTTP; the repositories know SQL; this class knows
 * "what a page of posts is".
 *
 * <p>In Phase 3 this is exactly where Redis caching will be added (cache-aside), without touching
 * the controller or the SQL.
 */
@Service
public class PostService {

    private final PostRepository postRepository;
    private final PostQueryRepository postQueryRepository;

    public PostService(PostRepository postRepository, PostQueryRepository postQueryRepository) {
        this.postRepository = postRepository;
        this.postQueryRepository = postQueryRepository;
    }

    /**
     * One page of published posts.
     *
     * <p>Trick: ask the database for {@code pageSize + 1} rows. If we get the extra row, there IS
     * a next page, and the cursor points at the last row we actually return. This avoids a second
     * {@code COUNT(*)} query, which gets slow on big tables.
     *
     * <p>{@code readOnly = true} tells Spring/Hibernate/the driver no writes will happen, which lets
     * them skip some work (e.g. Hibernate dirty checking) and could route to a read replica later.
     */
    @Transactional(readOnly = true)
    public CursorPage<PostSummary> listPublished(Section section, String tag, String cursorToken, int pageSize) {
        Cursor after = (cursorToken == null || cursorToken.isBlank()) ? null : Cursor.decode(cursorToken);
        String normalizedTag = (tag == null || tag.isBlank()) ? null : tag.trim().toLowerCase();

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

    @Transactional(readOnly = true)
    public PostDetail getPublishedBySlug(String slug) {
        return postRepository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                .map(PostDetail::from)                            // Optional<Post> -> Optional<PostDetail>
                .orElseThrow(() -> new PostNotFoundException(slug)); // empty -> 404 (see ApiExceptionHandler)
    }

    @Transactional(readOnly = true)
    public List<SectionCount> sections() {
        return postQueryRepository.countPublishedBySection();
    }
}
