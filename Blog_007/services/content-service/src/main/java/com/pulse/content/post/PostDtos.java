package com.pulse.content.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs = Data Transfer Objects: the exact JSON shapes our API returns.
 *
 * <p>Why not return the {@link Post} entity directly?
 * <ol>
 *   <li>The API contract stays stable even if the table changes.</li>
 *   <li>We never leak internal fields (version, bodyMd, status...).</li>
 *   <li>Lazy JPA relations + JSON serialization = surprise extra queries or errors.</li>
 * </ol>
 *
 * <p>A Java {@code record} is a compact, immutable data class: the compiler writes the
 * constructor, getters ({@code title()}), equals/hashCode and toString for us.
 * Jackson turns each record into JSON using the component names as keys.
 */
public final class PostDtos {

    private PostDtos() {
        // only a namespace for the records below
    }

    /** One card in a list (home page, section page). No body: lists must stay small and fast. */
    public record PostSummary(
            UUID id,
            String slug,
            String title,
            String summary,
            Section section,
            String coverImageUrl,
            int readingMinutes,
            Instant publishedAt,
            List<String> tags) {
    }

    /** The full article page. */
    public record PostDetail(
            UUID id,
            String slug,
            String title,
            String summary,
            String bodyHtml,
            Section section,
            String coverImageUrl,
            int readingMinutes,
            Instant publishedAt,
            Instant updatedAt,
            List<String> tags) {

        /** A "static factory" that converts an entity into the DTO. */
        static PostDetail from(Post post) {
            return new PostDetail(
                    post.getId(),
                    post.getSlug(),
                    post.getTitle(),
                    post.getSummary(),
                    post.getBodyHtml(),
                    post.getSection(),
                    post.getCoverImageUrl(),
                    post.getReadingMinutes(),
                    post.getPublishedAt(),
                    post.getUpdatedAt(),
                    post.getTags().stream().map(Tag::getName).sorted().toList());
        }
    }

    /**
     * A page of results for keyset ("cursor") pagination.
     * {@code nextCursor} is null on the last page; otherwise the client sends it back as
     * {@code ?cursor=...} to get the next page.
     */
    public record CursorPage<T>(List<T> items, String nextCursor) {
    }

    /** Section with how many published posts it has: GET /api/v1/sections */
    public record SectionCount(Section section, long posts) {
    }
}
