package dev.sibarum.suite.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same answers as {@code ViCompare} in the installer's {@code update.ps1}. */
class VersionsTest {

    @Test
    void numbersAreComparedAsNumbers() {
        assertTrue(Versions.compare("0.10.0", "0.9.0") > 0);
        assertTrue(Versions.compare("0.1.0", "0.1.1") < 0);
        assertTrue(Versions.compare("1.0", "0.99.99") > 0);
    }

    @Test
    void aMissingNumberIsZero() {
        assertEquals(0, Versions.compare("1", "1.0.0"));
        assertEquals(0, Versions.compare("v1.2", "1.2.0"));
    }

    @Test
    void aPrereleaseIsOlderThanItsRelease() {
        assertTrue(Versions.compare("0.2.0-rc1", "0.2.0") < 0);
        assertTrue(Versions.compare("0.2.0", "0.2.0-SNAPSHOT") > 0);
        assertTrue(Versions.compare("0.2.0-rc1", "0.1.9") > 0);
        assertEquals(0, Versions.compare("0.2.0-rc1", "0.2.0-rc2"));
    }

    @Test
    void buildMetadataIsIgnored() {
        assertEquals(0, Versions.compare("1.0.0+abc", "1.0.0"));
    }

    @Test
    void textThatIsNotAVersionIsZero() {
        assertEquals(0, Versions.compare("banana", "0.0"));
        assertTrue(Versions.compare("0.0.1", "banana") > 0);
    }
}
