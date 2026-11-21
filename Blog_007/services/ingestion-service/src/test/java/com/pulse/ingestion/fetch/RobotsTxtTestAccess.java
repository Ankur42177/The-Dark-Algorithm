package com.pulse.ingestion.fetch;

import java.util.List;

/** Test helper exposing the package-private robots.txt parser. */
public final class RobotsTxtTestAccess {
    private RobotsTxtTestAccess() {
    }

    public static List<String> disallows(String robots) {
        return RobotsTxt.disallowsFor(robots);
    }
}
