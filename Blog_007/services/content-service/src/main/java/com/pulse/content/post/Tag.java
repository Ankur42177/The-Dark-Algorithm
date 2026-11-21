package com.pulse.content.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tag such as "kafka" or "arsenal". Posts and tags are many-to-many:
 * one post has many tags, one tag is on many posts. The link lives in the
 * {@code post_tags} join table (see V1 migration).
 */
@Entity                 // "this class maps to a database table"
@Table(name = "tags")   // ...called "tags"
public class Tag {

    @Id                                                 // primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY) // the DB generates it (bigint identity column)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** JPA needs a no-argument constructor to create objects from rows. {@code protected} = not for us. */
    protected Tag() {
    }

    public Tag(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
