package com.pulse.content.post;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TagRepository extends JpaRepository<Tag, Long> {

    /** Derived query: SELECT … FROM tags WHERE name IN (…) — one query for all tags of a post. */
    List<Tag> findByNameIn(Collection<String> names);
}
