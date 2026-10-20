package dev.sibarum.suite.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseTest {

    /** The shape vexelray-installer publishes, cut down to what is read. */
    static String manifest(String latest, String notes) {
        return """
                {
                  "id": "pix", "latest": "%s",
                  "versions": [
                    { "version": "0.1.0", "notes": "", "platforms": { "windows": { "binaries": [] } } },
                    { "version": "%s", "notes": "%s",
                      "platforms": { "windows": { "binaries": [
                        { "name": "pix", "size": 43536384, "icon": { "size": 25450 } }
                      ] } } }
                  ]
                }""".formatted(latest, latest, notes);
    }

    @Test
    void readsTheLatestVersionItsNotesAndItsSize() {
        Release r = Release.latest(manifest("0.2.0", "  Faster.\\nFewer bugs. ")).orElseThrow();
        assertEquals("0.2.0", r.version());
        assertEquals("Faster.\nFewer bugs.", r.notes());
        assertEquals(43536384L + 25450L, r.bytes());
    }

    @Test
    void aByteOrderMarkIsSkipped() {
        assertTrue(Release.latest("﻿" + manifest("0.2.0", "")).isPresent());
    }

    @Test
    void noWindowsBuildIsNoRelease() {
        String m = """
                { "latest": "0.2.0", "versions": [ { "version": "0.2.0", "platforms": { "linux": {} } } ] }""";
        assertEquals(Optional.empty(), Release.latest(m));
    }

    @Test
    void aLatestThatIsNotListedIsNoRelease() {
        assertEquals(Optional.empty(), Release.latest("""
                { "latest": "9.9.9", "versions": [] }"""));
    }

    @Test
    void notJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> Release.latest("<html>404</html>"));
    }

    @Test
    void theCheckComparesWithWhatIsInstalled() {
        assertInstanceOf(Updates.Check.Available.class, Updates.compare("0.1.0", manifest("0.2.0", "")));
        assertInstanceOf(Updates.Check.Current.class, Updates.compare("0.2.0", manifest("0.2.0", "")));
        assertInstanceOf(Updates.Check.Current.class, Updates.compare("0.3.0-SNAPSHOT", manifest("0.2.0", "")));
        assertInstanceOf(Updates.Check.Failed.class, Updates.compare("0.1.0", "not json"));
        assertInstanceOf(Updates.Check.Failed.class, Updates.compare("0.1.0", "{}"));
    }
}
