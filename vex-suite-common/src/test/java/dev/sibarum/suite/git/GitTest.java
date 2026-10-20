package dev.sibarum.suite.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against a real repository, made fresh for each test. Skipped where git is not installed. */
class GitTest {

    private final Git git = Git.onPath();

    @TempDir
    Path dir;

    private Path repo;

    @BeforeEach
    void repository() throws IOException {
        assumeTrue(installed(), "git is not on the PATH");
        repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("sub"));
        Files.writeString(repo.resolve("sub/a.txt"), "a\n");
        Files.writeString(repo.resolve(".gitignore"), "target/\n");
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/x"), "x\n");
        git("init", "-q", "-b", "main", ".");
        git("add", ".");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
    }

    @Test
    void seesWhatChangedInTheWorkingTree() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Files.writeString(repo.resolve("sub/new.txt"), "n\n");
        Status s = git.status(repo).orElseThrow();
        assertEquals("main", s.branch().head());
        assertEquals(Status.State.MODIFIED, s.at(repo.resolve("sub/a.txt")));
        assertEquals(Status.State.UNTRACKED, s.at(repo.resolve("sub/new.txt")));
        assertEquals(Status.State.IGNORED, s.at(repo.resolve("target")));
        assertNull(s.at(repo.resolve(".gitignore")));
        assertEquals(new Status.Inside(0, 1, 1), s.inside(repo.resolve("sub")));
    }

    @Test
    void stagingShowsAsStaged() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        git("add", "sub/a.txt");
        assertEquals(Status.State.STAGED, git.status(repo).orElseThrow().at(repo.resolve("sub/a.txt")));
        Files.writeString(repo.resolve("sub/a.txt"), "changed again\n");
        assertEquals(Status.State.STAGED_AND_MODIFIED,
                git.status(repo).orElseThrow().at(repo.resolve("sub/a.txt")));
    }

    @Test
    void askedFromASubfolderItStillKnowsTheRoot() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Status s = git.status(repo.resolve("sub")).orElseThrow();
        assertEquals(Status.State.MODIFIED, s.at(repo.resolve("sub/a.txt")));
    }

    @Test
    void aFolderSpelledInAnotherCaseIsTheSameFolder() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "Windows only");
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Path shouted = Path.of(repo.toString().toUpperCase(Locale.ROOT));
        Status s = git.status(shouted).orElseThrow();
        assertEquals(Status.State.MODIFIED, s.at(shouted.resolve("SUB/a.txt")));
    }

    @Test
    void outsideARepositoryThereIsNothing() throws IOException {
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertEquals(Optional.empty(), git.status(plain));
    }

    @Test
    void withoutGitThereIsNothingAndNoError() {
        assertFalse(new Git("no-such-git-executable").status(repo).isPresent());
    }

    @Test
    void aRunKeepsWhatItAskedAndWhatCameBack() throws IOException {
        Git.Result r = git.run(repo, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        assertTrue(r.ok());
        assertEquals("main", r.text().strip());
        assertEquals(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), r.command());
    }

    private void git(String... args) throws IOException {
        Git.Result r = git.run(repo, List.of(args));
        assumeTrue(r.ok(), () -> "git " + String.join(" ", args) + " failed: " + r.err());
    }

    private boolean installed() {
        try {
            return git.run(Path.of(System.getProperty("java.io.tmpdir")), List.of("--version")).ok();
        } catch (IOException e) {
            return false;
        }
    }
}
