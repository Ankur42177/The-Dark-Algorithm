package com.pulse.content.post;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository: we write only the INTERFACE, Spring generates the class at startup.
 *
 * <p>{@code JpaRepository<Post, UUID>} gives us save(), findById(), findAll(), delete()... for free.
 * Extra methods are derived from their NAME:
 * {@code findBySlugAndStatus} becomes
 * <pre>select p from Post p where p.slug = ?1 and p.status = ?2</pre>
 *
 * <p>Used for single-entity reads (and, from Phase 4, writes). The paginated LIST queries live in
 * {@link PostQueryRepository} as hand-written SQL, so you can see exactly what hits the index.
 */
public interface PostRepository extends JpaRepository<Post, UUID> {

    /**
     * {@code @EntityGraph} tells Hibernate: "load the tags in the SAME query (a JOIN)".
     * Without it, reading post.getTags() later would fire a second query. For one post that is
     * harmless; in a list of 20 posts it becomes 1 + 20 queries — the infamous N+1 problem.
     */
    @EntityGraph(attributePaths = "tags")
    Optional<Post> findBySlugAndStatus(String slug, PostStatus status);

    /** Admin: load any post (any status) with its tags. */
    @EntityGraph(attributePaths = "tags")
    Optional<Post> findWithTagsById(UUID id);

    boolean existsBySlug(String slug);
}
