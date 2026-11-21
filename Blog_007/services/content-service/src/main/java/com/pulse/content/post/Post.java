package com.pulse.content.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity = a Java object that Hibernate keeps in sync with one row of the {@code posts} table.
 *
 * <p>The table itself is created by Flyway ({@code db/migration/V1__create_posts_and_tags.sql}),
 * NOT by Hibernate. {@code spring.jpa.hibernate.ddl-auto=validate} only checks that this class
 * and the table agree, and refuses to start if they don't.
 *
 * <p>Note what is NOT mapped: {@code search_tsv}. It is a generated column that Postgres
 * computes by itself for full-text search, so Java never needs to read or write it.
 */
@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID) // Hibernate creates a random UUID before INSERT
    private UUID id;

    @Column(nullable = false, unique = true, length = 160)
    private String slug;                 // URL-friendly id: /posts/kafka-consumer-groups-explained

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 400)
    private String summary;              // short teaser shown on cards and used as SEO description

    @Column(name = "body_md", nullable = false)
    private String bodyMd;               // what Ankur writes (Markdown)

    @Column(name = "body_html", nullable = false)
    private String bodyHtml;             // rendered + sanitized ONCE at publish time (Phase 4)

    @Enumerated(EnumType.STRING)         // store "TECH", not 0/1/2 (numbers break if the enum order changes)
    @Column(nullable = false, length = 20)
    private Section section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(name = "reading_minutes", nullable = false)
    private int readingMinutes;

    @Column(name = "published_at")
    private Instant publishedAt;         // Instant = a point in time in UTC (maps to timestamptz)

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking. Every UPDATE runs "... WHERE id = ? AND version = ?" and bumps the number.
     * If someone else saved first, 0 rows match and Hibernate throws an exception instead of
     * silently overwriting their change ("lost update"). Used by the admin editor in Phase 4.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /**
     * Many-to-many through the post_tags join table.
     * LAZY = tags are NOT loaded until someone touches post.getTags(). That avoids loading data
     * we don't need, but can cause the "N+1 queries" problem in lists — see PostRepository.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    protected Post() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getBodyMd() { return bodyMd; }
    public String getBodyHtml() { return bodyHtml; }
    public Section getSection() { return section; }
    public PostStatus getStatus() { return status; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public int getReadingMinutes() { return readingMinutes; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Set<Tag> getTags() { return tags; }
}
