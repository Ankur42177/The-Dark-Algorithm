package com.pulse.content.post;

import com.pulse.content.post.PostDtos.PostSummary;
import com.pulse.content.post.PostDtos.SectionCount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The READ side for lists: plain SQL through Spring's {@link JdbcClient}.
 *
 * <p>Why SQL here instead of JPA?
 * <ul>
 *   <li>Lists are the hottest, most performance-sensitive queries. With SQL you see exactly what
 *       runs, and can paste it into {@code EXPLAIN ANALYZE}.</li>
 *   <li>We select only the columns a card needs (no 50 KB body_html per row).</li>
 *   <li>Tags come back as a Postgres array in the SAME query, so there is no N+1.</li>
 * </ul>
 * This "use the ORM for writes, SQL for hot reads" split is a mini version of CQRS.
 */
@Repository // a @Component for data access; also translates SQL exceptions into Spring's DataAccessException
public class PostQueryRepository {

    private final JdbcClient jdbc;

    /** Constructor injection: Spring sees this constructor and passes in its JdbcClient bean. */
    public PostQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keyset pagination over published posts, newest first.
     *
     * @param section optional filter (null = all sections)
     * @param tag     optional filter (null = any tag)
     * @param after   optional cursor (null = first page)
     * @param limit   how many rows to fetch (the service asks for pageSize + 1 to detect "has more")
     */
    public List<PostSummary> findPublished(Section section, String tag, Cursor after, int limit) {
        // Build the WHERE clause from only the filters that were given. Every value goes in as a
        // named PARAMETER (:section), never glued into the string: this is what prevents SQL injection.
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.slug, p.title, p.summary, p.section, p.cover_image_url,
                       p.reading_minutes, p.published_at,
                       ARRAY(SELECT t.name
                               FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                              WHERE pt.post_id = p.id
                              ORDER BY t.name) AS tags
                  FROM posts p
                 WHERE p.status = 'PUBLISHED'
                """);
        List<String> conditions = new ArrayList<>();
        if (section != null) {
            conditions.add("p.section = :section");
        }
        if (tag != null) {
            conditions.add("""
                    EXISTS (SELECT 1 FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                             WHERE pt.post_id = p.id AND t.name = :tag)""");
        }
        if (after != null) {
            // Row-value comparison: "strictly after the last row of the previous page".
            // Postgres can answer this by jumping straight into the index — no rows are skipped.
            conditions.add("(p.published_at, p.id) < (:afterPublishedAt, :afterId)");
        }
        conditions.forEach(c -> sql.append("   AND ").append(c).append('\n'));
        sql.append(" ORDER BY p.published_at DESC, p.id DESC\n LIMIT :limit");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (section != null) {
            statement = statement.param("section", section.name());
        }
        if (tag != null) {
            statement = statement.param("tag", tag);
        }
        if (after != null) {
            statement = statement
                    .param("afterPublishedAt", Timestamp.from(after.publishedAt()))
                    .param("afterId", after.id());
        }
        return statement.query(PostQueryRepository::mapSummary).list();
    }

    /** Counts per section for GET /api/v1/sections. */
    public List<SectionCount> countPublishedBySection() {
        return jdbc.sql("""
                        SELECT section, count(*) AS posts
                          FROM posts
                         WHERE status = 'PUBLISHED'
                         GROUP BY section
                         ORDER BY section
                        """)
                .query((rs, rowNum) -> new SectionCount(
                        Section.valueOf(rs.getString("section")),
                        rs.getLong("posts")))
                .list();
    }

    /** Titles for a set of slugs (trending strip). One query with = ANY(array), not one per slug. */
    public List<PostDtos.TrendingItem> findTrendingInfo(List<String> slugs) {
        if (slugs.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT slug, title, section
                          FROM posts
                         WHERE status = 'PUBLISHED' AND slug = ANY(:slugs)
                        """)
                .param("slugs", slugs.toArray(String[]::new))
                .query((rs, n) -> new PostDtos.TrendingItem(
                        rs.getString("slug"), rs.getString("title"), Section.valueOf(rs.getString("section")), 0))
                .list();
    }

    /**
     * Write-behind target: add {@code views} to today's row for this post (UPSERT).
     * ON CONFLICT = "if the (post_id, day) row already exists, add to it instead of failing".
     * @return 1 if the post exists, 0 if it was deleted meanwhile
     */
    public int addDailyViews(String slug, long views) {
        return jdbc.sql("""
                        INSERT INTO post_daily_views (post_id, day, views)
                        SELECT id, (now() AT TIME ZONE 'UTC')::date, :views FROM posts WHERE slug = :slug
                        ON CONFLICT (post_id, day) DO UPDATE SET views = post_daily_views.views + EXCLUDED.views
                        """)
                .param("views", views)
                .param("slug", slug)
                .update();
    }

    /** RowMapper: turns ONE row of the ResultSet into ONE Java object. */
    private static PostSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new PostSummary(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("summary"),
                Section.valueOf(rs.getString("section")),
                rs.getString("cover_image_url"),
                rs.getInt("reading_minutes"),
                rs.getTimestamp("published_at").toInstant(),
                toStringList(rs.getArray("tags")));
    }

    private static List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        return Arrays.asList((String[]) sqlArray.getArray());
    }
}
