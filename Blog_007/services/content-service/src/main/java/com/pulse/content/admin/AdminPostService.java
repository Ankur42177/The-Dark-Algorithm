package com.pulse.content.admin;

import com.pulse.content.admin.AdminDtos.AdminPostRow;
import com.pulse.content.admin.AdminDtos.AdminPostView;
import com.pulse.content.admin.AdminDtos.PostRequest;
import com.pulse.content.events.OutboxWriter;
import com.pulse.content.markdown.MarkdownRenderer;
import com.pulse.events.EventTypes;
import com.pulse.content.post.Post;
import com.pulse.content.post.PostRepository;
import com.pulse.content.post.Tag;
import com.pulse.content.post.TagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin use cases: create, edit, publish, schedule, unpublish, delete.
 *
 * <p>Admin reads go straight to Postgres, never through Redis: "read-your-writes" — Ankur must see his
 * own edit immediately (PLAN §16.5).
 *
 * <p>Phase 6: every change also writes an EVENT into the outbox table, in the SAME transaction.
 * Kafka consumers then evict caches, refresh the website and (Phase 8) re-embed the post for the chatbot.
 */
@Service
public class AdminPostService {

    public static class NotFound extends RuntimeException {
        public NotFound(UUID id) {
            super("No post with id " + id);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String msg) {
            super(msg);
        }
    }

    private final PostRepository posts;
    private final TagRepository tags;
    private final MarkdownRenderer markdown;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;

    public AdminPostService(PostRepository posts, TagRepository tags, MarkdownRenderer markdown,
                            OutboxWriter outbox, JdbcClient jdbc) {
        this.posts = posts;
        this.tags = tags;
        this.markdown = markdown;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AdminPostRow> list() {
        return jdbc.sql("""
                        SELECT id, slug, title, section, status, published_at, updated_at, version
                          FROM posts ORDER BY updated_at DESC LIMIT 200""")
                .query((rs, n) -> new AdminPostRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                        com.pulse.content.post.Section.valueOf(rs.getString("section")),
                        com.pulse.content.post.PostStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getLong("version")))
                .list();
    }

    @Transactional(readOnly = true)
    public AdminPostView get(UUID id) {
        return AdminPostView.from(load(id));
    }

    @Transactional
    public AdminPostView create(PostRequest req) {
        String slug = req.slug() != null ? req.slug() : uniqueSlug(slugify(req.title()));
        if (posts.existsBySlug(slug)) {
            throw new Conflict("Slug '" + slug + "' is already used");
        }
        Post p = Post.draft(slug, req.title(), req.summary(), req.section(), req.coverImageUrl(),
                req.bodyMd(), markdown.toSafeHtml(req.bodyMd()), MarkdownRenderer.readingMinutes(req.bodyMd()), resolveTags(req.tags()));
        try {
            return AdminPostView.from(posts.saveAndFlush(p));
        } catch (DataIntegrityViolationException e) {        // two creates raced for the same slug
            throw new Conflict("Slug '" + slug + "' is already used");
        }
    }

    /**
     * Edit with OPTIMISTIC LOCKING: the client sends the version it loaded. If somebody saved in between,
     * versions differ → 409 Conflict instead of silently overwriting their change.
     */
    @Transactional
    public AdminPostView update(UUID id, PostRequest req) {
        Post p = load(id);
        if (req.version() == null || req.version() != p.getVersion()) {
            throw new Conflict("This post was changed by someone else (version " + p.getVersion()
                    + ", you had " + req.version() + "). Reload and re-apply your edit.");
        }
        p.edit(req.title(), req.summary(), req.section(), req.coverImageUrl(), req.bodyMd(),
                markdown.toSafeHtml(req.bodyMd()), MarkdownRenderer.readingMinutes(req.bodyMd()), resolveTags(req.tags()));
        try {
            posts.saveAndFlush(p);          // UPDATE … WHERE id = ? AND version = ?  (Hibernate adds the version check)
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new Conflict("This post was changed by someone else. Reload and re-apply your edit.");
        }
        outbox.postEvent(EventTypes.POST_UPDATED, p, null);
        return AdminPostView.from(p);
    }

    @Transactional
    public AdminPostView publish(UUID id) {
        Post p = load(id);
        transition(() -> p.publish(Instant.now()));
        posts.saveAndFlush(p);
        outbox.postEvent(EventTypes.POST_PUBLISHED, p, null);     // same transaction: both or neither
        return AdminPostView.from(p);
    }

    @Transactional
    public AdminPostView schedule(UUID id, Instant at) {
        Post p = load(id);
        transition(() -> p.schedule(at, Instant.now()));
        posts.saveAndFlush(p);
        outbox.postEvent(EventTypes.POST_UPDATED, p, null);
        return AdminPostView.from(p);
    }

    @Transactional
    public AdminPostView unpublish(UUID id) {
        Post p = load(id);
        transition(p::unpublish);
        posts.saveAndFlush(p);
        outbox.postEvent(EventTypes.POST_UNPUBLISHED, p, null);
        return AdminPostView.from(p);
    }

    @Transactional
    public void delete(UUID id) {
        Post p = load(id);
        outbox.postEvent(EventTypes.POST_UNPUBLISHED, p, "DELETED");   // capture the data before the row is gone
        posts.delete(p);
    }

    // ------------------------------------------------------------------ helpers

    private Post load(UUID id) {
        return posts.findWithTagsById(id).orElseThrow(() -> new NotFound(id));
    }

    private static void transition(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new Conflict(e.getMessage());
        }
    }

    private Set<Tag> resolveTags(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> wanted = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Tag> result = new HashSet<>(tags.findByNameIn(wanted));
        Set<String> existing = result.stream().map(Tag::getName).collect(Collectors.toSet());
        for (String name : wanted) {
            if (!existing.contains(name)) {
                result.add(tags.save(new Tag(name)));
            }
        }
        return result;
    }

    /** "Kafka Consumer Groups — Explained!" → "kafka-consumer-groups-explained" */
    static String slugify(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String s = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (s.isEmpty()) {
            s = "post";
        }
        return s.length() > 150 ? s.substring(0, 150).replaceAll("-$", "") : s;
    }

    private String uniqueSlug(String base) {
        String slug = base;
        for (int i = 2; posts.existsBySlug(slug); i++) {
            slug = base + "-" + i;
        }
        return slug;
    }
}
