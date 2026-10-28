package dev.sibarum.suite.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How {@link Git#push} reaches a remote: the ways to try, in order. Pure: a URL in, ways out, so it is tested without
 * a network.
 *
 * <p><b>An installed key first, the credential manager after.</b> A key in {@code ~/.ssh} asks nobody anything, so
 * where there is one, an HTTPS remote is pushed to over SSH first, its URL rewritten for that one push
 * ({@code url.<ssh>.insteadOf}, so the remote and its tracking branches stay as they are). Over SSH nothing may prompt:
 * {@code BatchMode} turns a passphrase, an unknown host or a key the host refuses into a failure at once. When SSH
 * cannot reach the repository, the push goes over HTTPS, where git's credential helper, normally Git Credential
 * Manager, may open its own window to sign in. A remote that is SSH already is tried as it is, then over HTTPS the same
 * way. Anything else, a path or {@code git://}, is pushed to as it is.
 */
final class Push {

    /** The private keys ssh looks for by default, RSA first. */
    static final List<String> IDENTITIES = List.of("id_rsa", "id_ecdsa", "id_ed25519");

    /**
     * One way to push: over SSH or not, and the {@code -c} options that put it there.
     *
     * @param ssh    whether it goes over SSH, and so must not prompt
     * @param config {@code -c name=value} pairs before {@code push}
     */
    record Way(boolean ssh, List<String> config) {

        Way {
            config = List.copyOf(config);
        }
    }

    private Push() {
    }

    /** Whether {@code sshDir} holds one of the keys ssh tries by default. */
    static boolean identity(Path sshDir) {
        for (String name : IDENTITIES) {
            if (Files.isRegularFile(sshDir.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ways to push to {@code url}, in the order they are tried; the next is tried only when SSH could not reach
     * the repository ({@link #unreachable}). A null {@code url} is one git could not name: pushed as git sees fit, and
     * git says why it cannot.
     *
     * @param identity whether a key is installed ({@link #identity})
     */
    static List<Way> ways(String url, boolean identity) {
        if (url == null || url.isBlank()) {
            return List.of(new Way(false, List.of()));
        }
        Remote r = Remote.of(url.strip());
        if (r == null) {
            return List.of(new Way(false, List.of()));
        }
        List<Way> ways = new ArrayList<>();
        if (r.ssh()) {
            ways.add(new Way(true, List.of()));
            ways.add(new Way(false, insteadOf("https://" + r.host() + "/", r.prefix())));
        } else {
            if (identity && r.port() == null) {
                ways.add(new Way(true, insteadOf("git@" + r.host() + ":", r.prefix())));
            }
            ways.add(new Way(false, List.of()));
        }
        return ways;
    }

    /** Whether a push over SSH failed to reach the repository at all, so another way may: refused, unknown, gone. */
    static boolean unreachable(Git.Result r) {
        return !r.ok() && r.err().contains("Could not read from remote repository");
    }

    private static List<String> insteadOf(String to, String from) {
        return List.of("-c", "url." + to + ".insteadOf=" + from);
    }

    /**
     * A remote's URL, as far as choosing a way goes.
     *
     * @param ssh    whether it is an SSH one: {@code ssh://…}, or scp-like {@code git@host:owner/repo}
     * @param host   the host alone
     * @param port   the port, if the URL names one; null if not
     * @param prefix the URL up to the repository's path, which an {@code insteadOf} rewrites
     */
    record Remote(boolean ssh, String host, String port, String prefix) {

        /** Read {@code url}; null for a path, {@code file://}, {@code git://}, or anything not over HTTP or SSH. */
        static Remote of(String url) {
            int scheme = url.indexOf("://");
            if (scheme < 0) {
                // scp-like: [user@]host:path. A drive letter, C:/…, is a path.
                int colon = url.indexOf(':');
                int slash = url.indexOf('/');
                if (colon <= 1 || (slash >= 0 && slash < colon)) {
                    return null;
                }
                String authority = url.substring(0, colon);
                return new Remote(true, authority.substring(authority.lastIndexOf('@') + 1), null,
                        url.substring(0, colon + 1));
            }
            String protocol = url.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (!protocol.equals("ssh") && !protocol.equals("https") && !protocol.equals("http")) {
                return null;
            }
            int end = url.indexOf('/', scheme + 3);
            if (end < 0) {
                return null;
            }
            String authority = url.substring(scheme + 3, end);
            String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
            String host = hostPort;
            String port = null;
            int p = hostPort.lastIndexOf(':');
            if (p >= 0 && !hostPort.endsWith("]")) {
                host = hostPort.substring(0, p);
                port = hostPort.substring(p + 1);
            }
            if (host.isEmpty()) {
                return null;
            }
            return new Remote(protocol.equals("ssh"), host, port, url.substring(0, end + 1));
        }
    }
}
