package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which ways a push tries, and in what order. No git, no network. */
class PushTest {

    private static final Push.Way AS_IT_IS = new Push.Way(false, List.of());

    @Test
    void anHttpsRemoteGoesOverSshFirstWhenAKeyIsInstalled() {
        assertEquals(List.of(
                        new Push.Way(true, List.of("-c", "url.git@github.com:.insteadOf=https://github.com/")),
                        AS_IT_IS),
                Push.ways("https://github.com/sibarum/vex.git", true));
    }

    @Test
    void withoutAKeyAnHttpsRemoteIsTheCredentialManagersAlone() {
        assertEquals(List.of(AS_IT_IS), Push.ways("https://github.com/sibarum/vex.git", false));
    }

    @Test
    void aUserInTheUrlIsPartOfWhatIsRewritten() {
        assertEquals(List.of("-c", "url.git@github.com:.insteadOf=https://jw@github.com/"),
                Push.ways("https://jw@github.com/sibarum/vex.git", true).get(0).config());
    }

    @Test
    void anHttpsRemoteOnAPortOfItsOwnIsNotGuessedAtOverSsh() {
        assertEquals(List.of(AS_IT_IS), Push.ways("https://git.example.com:8443/team/vex.git", true));
    }

    @Test
    void anSshRemoteIsTriedAsItIsThenOverHttps() {
        assertEquals(List.of(new Push.Way(true, List.of()),
                        new Push.Way(false, List.of("-c", "url.https://github.com/.insteadOf=git@github.com:"))),
                Push.ways("git@github.com:sibarum/vex.git", false));
        assertEquals(List.of(new Push.Way(true, List.of()),
                        new Push.Way(false,
                                List.of("-c", "url.https://host.example/.insteadOf=ssh://git@host.example:2222/"))),
                Push.ways("ssh://git@host.example:2222/team/vex.git", true));
    }

    @Test
    void aPathIsPushedToAsItIs() {
        assertEquals(List.of(AS_IT_IS), Push.ways("C:/Users/jw/origin.git", true));
        assertEquals(List.of(AS_IT_IS), Push.ways("/srv/git/origin.git", true));
        assertEquals(List.of(AS_IT_IS), Push.ways("../origin.git", true));
        assertEquals(List.of(AS_IT_IS), Push.ways("file:///srv/git/origin.git", true));
        assertEquals(List.of(AS_IT_IS), Push.ways(null, true));
        assertNull(Push.Remote.of("git://host/x.git"));
    }

    @Test
    void onlyAnSshFailureToReachTheRepositoryIsWorthAnotherWay() {
        Git.Result refused = result(128, "git@github.com: Permission denied (publickey).\r\n"
                + "fatal: Could not read from remote repository.\n");
        Git.Result rejected = result(1, "To github.com:sibarum/vex.git\n"
                + " ! [rejected]        main -> main (fetch first)\n"
                + "error: failed to push some refs to 'github.com:sibarum/vex.git'\n");
        assertTrue(Push.unreachable(refused));
        assertFalse(Push.unreachable(rejected));
        assertFalse(Push.unreachable(result(0, "")));
    }

    @Test
    void aKeyIsOneOfTheFilesSshLooksForByDefault(@TempDir Path ssh) throws IOException {
        assertFalse(Push.identity(ssh));
        Files.writeString(ssh.resolve("id_rsa.pub"), "ssh-rsa AAAA");
        Files.writeString(ssh.resolve("known_hosts"), "");
        assertFalse(Push.identity(ssh), "a public key alone signs nothing");
        Files.writeString(ssh.resolve("id_rsa"), "-----BEGIN OPENSSH PRIVATE KEY-----");
        assertTrue(Push.identity(ssh));
        assertFalse(Push.identity(ssh.resolve("missing")));
    }

    private static Git.Result result(int exit, String err) {
        return new Git.Result(List.of("git", "push"), Path.of("."), exit, new byte[0], err, Duration.ZERO);
    }
}
