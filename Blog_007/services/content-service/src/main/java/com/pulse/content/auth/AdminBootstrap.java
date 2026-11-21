package com.pulse.content.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the single admin (PLAN §4.2) from environment variables when the app starts.
 * The password itself never exists anywhere — only its BCrypt hash (scripts/hash-password.sh).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthProperties props;
    private final AdminUserRepository users;

    public AdminBootstrap(AuthProperties props, AdminUserRepository users) {
        this.props = props;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hash = stripQuotes(props.adminPasswordHash());
        if (hash == null || hash.isBlank()) {
            log.warn("ADMIN_PASSWORD_HASH not set: admin login is disabled. Run scripts/hash-password.sh");
            return;
        }
        if (!hash.startsWith("{bcrypt}")) {
            throw new IllegalStateException("ADMIN_PASSWORD_HASH must be a {bcrypt} hash, never a plain password");
        }
        users.upsert(props.adminUsername(), props.adminDisplayName(), hash);
        log.info("Admin user '{}' ready", props.adminUsername());
    }

    /** .env values may be quoted so Docker Compose doesn't treat the '$' in BCrypt hashes as variables. */
    static String stripQuotes(String v) {
        if (v != null && v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\""))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
