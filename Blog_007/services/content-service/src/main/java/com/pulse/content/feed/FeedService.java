package com.pulse.content.feed;

import com.pulse.content.cache.PostCache;
import com.pulse.content.post.Cursor;
import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.Section;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;

@Service
public class FeedService {

    /** Bumped by the read-model consumer: every cached feed page with the old number becomes unreachable. */
    public static final String FEED_GENERATION = "cache:gen:feed";
    private static final TypeReference<CursorPage<FeedItem>> TYPE = new TypeReference<>() { };

    private final FeedRepository repo;
    private final PostCache cache;

    public FeedService(FeedRepository repo, PostCache cache) {
        this.repo = repo;
        this.cache = cache;
    }

    public CursorPage<FeedItem> page(Section section, String source, String cursorToken, int pageSize) {
        Cursor after = cursorToken == null || cursorToken.isBlank() ? null : Cursor.decode(cursorToken);
        String key = "cache:feed:" + cache.generation(FEED_GENERATION) + ":" + (section == null ? "ALL" : section.name()) + ":"
                + (source == null ? "-" : source) + ":" + (after == null ? "first" : cursorToken) + ":" + pageSize;
        return cache.getOrLoad("feed", key, Duration.ofSeconds(60), TYPE, () -> {
            List<FeedItem> rows = repo.find(section, source, after, pageSize + 1);
            boolean more = rows.size() > pageSize;
            List<FeedItem> page = more ? rows.subList(0, pageSize) : rows;
            String next = more ? new Cursor(page.getLast().publishedAt(), page.getLast().id()).encode() : null;
            return new CursorPage<>(List.copyOf(page), next);
        });
    }
}
