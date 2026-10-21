package dev.sibarum.suite.tty.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Short names come from kernel32, so these run where it is. Moved here from MainFrame with the code they test. */
@EnabledOnOs(OS.WINDOWS)
class ShortNameTest {

    private final WindowsPtyPlatform platform = new WindowsPtyPlatform();

    @Test
    void aNameThatFitsComesBackAsItself(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("PLAIN.TXT"));
        assertEquals(Optional.of("PLAIN.TXT"), platform.shortName(f));
    }

    @Test
    void aLongNameComesBackShortOrNotAtAll(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("glaze recipes 100% final!.pdf"));
        Optional<String> s = platform.shortName(f);
        // Whether a volume makes 8.3 names is its own setting; when it does, the name is in their narrow alphabet.
        s.ifPresent(n -> assertTrue(n.matches("[A-Z0-9~_.!#$%&'()\\-@^`{}]{1,12}") || n.equals(f.getFileName().toString()), n));
    }

    @Test
    void aFileThatIsNotThereHasNone(@TempDir Path dir) {
        assertEquals(Optional.empty(), platform.shortName(dir.resolve("missing.txt")));
    }
}
