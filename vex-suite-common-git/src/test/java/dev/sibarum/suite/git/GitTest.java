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
    void knowsWhereTheRepositoryKeepsItsIndex() throws IOException {
        Path gitDir = git.gitDir(repo.resolve("sub")).orElseThrow();
        assertTrue(Files.isRegularFile(gitDir.resolve("index")), gitDir.toString());
        assertTrue(Files.isRegularFile(gitDir.resolve("HEAD")));
        assertFalse(git.gitDir(Files.createDirectories(dir.resolve("plain"))).isPresent());
    }

    @Test
    void stagesAndUnstagesAChangeANewFileAndADeletion() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Files.writeString(repo.resolve("new.txt"), "n\n");
        Files.delete(repo.resolve(".gitignore"));
        assertTrue(git.stage(repo, List.of("sub/a.txt", "new.txt", ".gitignore")).ok());
        Status s = git.status(repo).orElseThrow();
        assertEquals(List.of(".gitignore", "new.txt", "sub/a.txt"),
                s.staged().stream().map(Status.Change::path).sorted().toList());

        Git.Result back = git.unstage(repo, List.of("sub/a.txt", "new.txt", ".gitignore"));
        assertTrue(back.ok(), back.err());
        s = git.status(repo).orElseThrow();
        assertEquals(List.of(), s.staged());
        assertEquals(Status.State.MODIFIED, s.at(repo.resolve("sub/a.txt")));
        assertEquals("changed\n", Files.readString(repo.resolve("sub/a.txt")), "the working tree is left alone");
    }

    @Test
    void everythingUnderAFolderIsStagedByItsDot() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Files.writeString(repo.resolve("sub/b.txt"), "new\n");
        Files.writeString(repo.resolve("outside.txt"), "not this\n");
        assertTrue(git.stage(repo.resolve("sub"), List.of(".")).ok());
        Status s = git.status(repo).orElseThrow();
        assertEquals(List.of("sub/a.txt", "sub/b.txt"), s.staged().stream().map(Status.Change::path).sorted().toList());
        assertEquals(Status.State.UNTRACKED, s.at(repo.resolve("outside.txt")));
        assertTrue(git.unstage(repo.resolve("sub"), List.of(".")).ok());
        assertEquals(List.of(), git.status(repo).orElseThrow().staged());
    }

    @Test
    void beforeTheFirstCommitUnstagingTakesTheFileOutOfTheIndex() throws IOException {
        Path fresh = Files.createDirectories(dir.resolve("fresh"));
        git.run(fresh, List.of("init", "-q", "-b", "main", "."));
        Files.writeString(fresh.resolve("a.txt"), "a\n");
        assertTrue(git.stage(fresh, List.of("a.txt")).ok());
        assertEquals(Status.State.ADDED, git.status(fresh).orElseThrow().at(fresh.resolve("a.txt")));
        Git.Result r = git.unstage(fresh, List.of("a.txt"));
        assertTrue(r.ok(), r.err());
        assertEquals(Status.State.UNTRACKED, git.status(fresh).orElseThrow().at(fresh.resolve("a.txt")));
    }

    @Test
    void aWriteThatCannotStartIsAFailedResultNotAnException() {
        Git.Result r = new Git("no-such-git-executable").stage(repo, List.of("x"));
        assertFalse(r.ok());
        assertEquals(-1, r.exit());
        assertFalse(r.err().isEmpty());
    }

    @Test
    void aRunKeepsWhatItAskedAndWhatCameBack() throws IOException {
        Git.Result r = git.run(repo, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        assertTrue(r.ok());
        assertEquals("main", r.text().strip());
        assertEquals(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), r.command());
    }

    @Test
    void aStagedFileIsListedOnceForEachSideItChangedOn() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        git("add", "sub/a.txt");
        Files.writeString(repo.resolve("sub/a.txt"), "changed again\n");
        Files.writeString(repo.resolve("new.txt"), "n\n");
        Status s = git.status(repo).orElseThrow();
        assertEquals(List.of("sub/a.txt"), s.staged().stream().map(Status.Change::path).toList());
        assertEquals(List.of("sub/a.txt", "new.txt"), s.unstaged().stream().map(Status.Change::path).toList());
    }

    @Test
    void countsLinesOnEachSide() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "b\nc\n");
        git("add", "sub/a.txt");
        Files.writeString(repo.resolve("sub/a.txt"), "b\nc\nd\n");
        assertEquals(new Numstat.Lines(2, 1, false), git.numstat(repo, true).at("sub/a.txt"));
        assertEquals(new Numstat.Lines(1, 0, false), git.numstat(repo, false).at("sub/a.txt"));
    }

    @Test
    void diffsEachSideAndAnUntrackedFile() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "b\n");
        git("add", "sub/a.txt");
        Files.writeString(repo.resolve("sub/a.txt"), "b\nc\n");
        Files.writeString(repo.resolve("sub/new file.txt"), "one\ntwo\n");

        Diff staged = git.diff(repo, "sub/a.txt", Diff.Side.STAGED);
        assertEquals(List.of(new Diff.Line(1, 0, Diff.Kind.REMOVED, "a"), new Diff.Line(0, 1, Diff.Kind.ADDED, "b")),
                staged.hunks().get(0).lines());

        Diff unstaged = git.diff(repo, "sub/a.txt", Diff.Side.UNSTAGED);
        assertEquals(1, unstaged.added());
        assertEquals(0, unstaged.removed());

        Diff untracked = git.diff(repo, "sub/new file.txt", Diff.Side.UNTRACKED);
        assertEquals(List.of(new Diff.Line(0, 1, Diff.Kind.ADDED, "one"), new Diff.Line(0, 2, Diff.Kind.ADDED, "two")),
                untracked.hunks().get(0).lines());

        assertTrue(git.diff(repo, ".gitignore", Diff.Side.UNSTAGED).hunks().isEmpty());
    }

    @Test
    void aCommitMessageGoesInOnStandardInputLinesAndAll() throws IOException {
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        git("add", "sub/a.txt");
        Git.Result r = git.run(repo, List.of("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-F", "-"),
                "Change a, \"quoted\"\n\nA second paragraph.\n", Git.TIMEOUT);
        assertTrue(r.ok(), r.err());
        List<Commit> log = git.log(repo, null, 10);
        assertEquals(2, log.size());
        assertEquals("Change a, \"quoted\"", log.get(0).subject());
        assertEquals(List.of("HEAD", "main"), log.get(0).refs());
        assertEquals("init", log.get(1).subject());
        assertEquals("t", log.get(1).author());
        assertEquals(1, git.log(repo, null, 1).size());
    }

    @Test
    void commitsWhatIsStagedAndOnlyThat() throws IOException {
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        Files.writeString(repo.resolve("left.txt"), "not staged\n");
        assertTrue(git.stage(repo, List.of("sub/a.txt")).ok());
        Git.Result r = git.commit(repo, "Change a\n\nWhy it changed.\n");
        assertTrue(r.ok(), r.err());
        assertEquals("Change a", git.log(repo, null, 1).get(0).subject());
        Status s = git.status(repo).orElseThrow();
        assertEquals(List.of(), s.staged());
        assertEquals(Status.State.UNTRACKED, s.at(repo.resolve("left.txt")));
        assertEquals(List.of("git", "commit", "-F", "-"), r.command());
    }

    @Test
    void aFailingHookCommitsNothingAndSaysWhy() throws IOException {
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        Path hook = repo.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\necho 'lint: a.txt is wrong' >&2\nexit 1\n");
        hook.toFile().setExecutable(true);
        Files.writeString(repo.resolve("sub/a.txt"), "changed\n");
        assertTrue(git.stage(repo, List.of("sub/a.txt")).ok());
        Git.Result r = git.commit(repo, "Change a");
        assertFalse(r.ok());
        assertTrue(r.err().contains("lint: a.txt is wrong"), r.err());
        assertEquals("init", git.log(repo, null, 1).get(0).subject());
        assertEquals(List.of("sub/a.txt"),
                git.status(repo).orElseThrow().staged().stream().map(Status.Change::path).toList());
    }

    @Test
    void nothingStagedIsAFailedCommit() throws IOException {
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        assertFalse(git.commit(repo, "Nothing").ok());
    }

    @Test
    void listsBranchesAndWhichIsCheckedOut() throws IOException {
        git("branch", "feature");
        List<Ref> refs = git.branches(repo);
        assertEquals(List.of("feature", "main"), refs.stream().map(Ref::name).toList());
        assertTrue(refs.get(1).head());
        assertFalse(refs.get(0).head());
    }

    @Test
    void beforeTheFirstCommitThereIsNoHistoryAndNoError() throws IOException {
        Path fresh = Files.createDirectories(dir.resolve("fresh"));
        git.run(fresh, List.of("init", "-q", "-b", "main", "."));
        assertEquals(List.of(), git.log(fresh, null, 10));
        assertEquals(List.of(), git.branches(fresh));
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
