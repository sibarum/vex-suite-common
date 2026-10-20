package dev.sibarum.suite.update;

import dev.vexelray.text.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The latest published version of an application, read from the {@code manifest.json} that
 * {@code vexelray-installer} publishes for it. Pure, so it is tested without the network.
 *
 * @param version the version, as the manifest's {@code latest} names it
 * @param notes   its release notes; empty when there are none
 * @param bytes   what an update downloads: the Windows build's files, together; 0 when the manifest does not say
 */
public record Release(String version, String notes, long bytes) {

    /**
     * The latest release in {@code manifest}, or empty when it names none, or none with a Windows build, which is the
     * only build the installer's updater can install.
     *
     * @throws IllegalArgumentException if it is not JSON
     */
    public static Optional<Release> latest(String manifest) {
        if (!(Json.parse(manifest.startsWith("﻿") ? manifest.substring(1) : manifest) instanceof Map<?, ?> top)
                || !(top.get("latest") instanceof String latest) || latest.isBlank()
                || !(top.get("versions") instanceof List<?> versions)) {
            return Optional.empty();
        }
        for (Object v : versions) {
            if (!(v instanceof Map<?, ?> entry) || !latest.equals(entry.get("version"))) {
                continue;
            }
            if (!(entry.get("platforms") instanceof Map<?, ?> platforms)
                    || !(platforms.get("windows") instanceof Map<?, ?> windows)) {
                return Optional.empty();
            }
            String notes = entry.get("notes") instanceof String n ? n.strip() : "";
            return Optional.of(new Release(latest, notes, bytes(windows)));
        }
        return Optional.empty();
    }

    /** Every binary's size and its icon's, which is what the installer downloads. */
    private static long bytes(Map<?, ?> windows) {
        long total = 0;
        if (windows.get("binaries") instanceof List<?> binaries) {
            for (Object b : binaries) {
                if (b instanceof Map<?, ?> binary) {
                    total += size(binary);
                    if (binary.get("icon") instanceof Map<?, ?> icon) {
                        total += size(icon);
                    }
                }
            }
        }
        return total;
    }

    private static long size(Map<?, ?> file) {
        return file.get("size") instanceof Number n ? n.longValue() : 0L;
    }
}
