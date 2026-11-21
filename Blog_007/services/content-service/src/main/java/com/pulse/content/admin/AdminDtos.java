package com.pulse.content.admin;

import com.pulse.content.post.Post;
import com.pulse.content.post.PostStatus;
import com.pulse.content.post.Section;
import com.pulse.content.post.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of the admin API. Validation annotations = the first line of input defence. */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** Create or edit a post. {@code version} is required on edit (optimistic locking). */
    public record PostRequest(
            @NotBlank @Size(max = 200) String title,
            @Pattern(regexp = "[a-z0-9-]{1,160}") String slug,          // optional on create: derived from title
            @Size(max = 400) String summary,
            @NotNull @Size(max = 200_000) String bodyMd,
            @NotNull Section section,
            @Size(max = 10) List<@Pattern(regexp = "[a-z0-9-]{1,50}") String> tags,
            @Pattern(regexp = "https://\\S{1,490}") String coverImageUrl,
            Long version) {
    }

    public record ScheduleRequest(@NotNull Instant publishAt) {
    }

    public record PreviewRequest(@NotNull @Size(max = 200_000) String bodyMd) {
    }

    public record PreviewResponse(String html, int readingMinutes) {
    }

    /** What the editor sees: includes Markdown, status and version (never shown to readers). */
    public record AdminPostView(UUID id, String slug, String title, String summary, String bodyMd, String bodyHtml,
                                Section section, PostStatus status, List<String> tags, String coverImageUrl,
                                int readingMinutes, Instant publishedAt, Instant updatedAt, long version) {
        static AdminPostView from(Post p) {
            return new AdminPostView(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getBodyMd(), p.getBodyHtml(),
                    p.getSection(), p.getStatus(), p.getTags().stream().map(Tag::getName).sorted().toList(),
                    p.getCoverImageUrl(), p.getReadingMinutes(), p.getPublishedAt(), p.getUpdatedAt(), p.getVersion());
        }
    }

    public record AdminPostRow(UUID id, String slug, String title, Section section, PostStatus status,
                               Instant publishedAt, Instant updatedAt, long version) {
    }
}
