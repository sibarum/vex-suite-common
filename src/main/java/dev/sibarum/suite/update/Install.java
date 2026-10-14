package dev.sibarum.suite.update;

import dev.vexelray.text.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * An application as {@code vexelray-installer} installed it: what its install record,
 * {@code <registryDir>\<id>.json}, says. The record is the only place that knows the installed version and where the
 * published manifest is; an application built from source has neither, and is not offered updates.
 *
 * @param id          the install's id, which is also the record's file name
 * @param name        what the application is called, for the words the user reads
 * @param version     the version installed
 * @param dir         the install folder, where {@code update.ps1} is
 * @param manifestUrl the published manifest, which says what the latest version is
 * @param executable  the installed executable: what is running, when it is this install that runs
 */
public record Install(String id, String name, String version, Path dir, String manifestUrl, Path executable) {

    /** The installer's updater, beside the executable. */
    public Path updater() {
        return dir.resolve("update.ps1");
    }

    /**
     * Whether the process running this code is this install's executable, and not a build of it somewhere else. Only
     * then is there something to update: a build from source would be replaced by nothing, and restarting it would
     * start the installed copy instead.
     */
    public boolean running() {
        return ProcessHandle.current().info().command().map(c -> same(Path.of(c), executable)).orElse(false);
    }

    /**
     * The install recorded as {@code id} under {@code registryDir}, or empty when there is none, or the record is not
     * one this can read. A record without a manifest URL or a version cannot be updated from here, and is empty too.
     */
    public static Optional<Install> find(Path registryDir, String id) {
        Path record = registryDir.resolve(id + ".json");
        if (!Files.isRegularFile(record)) {
            return Optional.empty();
        }
        Object parsed;
        try {
            String text = Files.readString(record, StandardCharsets.UTF_8);
            // PowerShell has written a byte-order mark before; JSON does not allow one.
            parsed = Json.parse(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> top)
                || !(top.get("version") instanceof String version) || version.isBlank()
                || !(top.get("installDir") instanceof String dir) || dir.isBlank()
                || !(top.get("manifestUrl") instanceof String url) || url.isBlank()
                || !(top.get("commands") instanceof Map<?, ?> commands)) {
            return Optional.empty();
        }
        Object command = commands.get(id);
        if (command == null && commands.size() == 1) {
            command = commands.values().iterator().next();
        }
        if (!(command instanceof Map<?, ?> c) || !(c.get("path") instanceof String exe) || exe.isBlank()) {
            return Optional.empty();
        }
        String name = top.get("name") instanceof String n && !n.isBlank() ? n : id;
        return Optional.of(new Install(id, name, version, Path.of(dir), url, Path.of(exe)));
    }

    /** The same file, as Windows compares names: case-blind, and through any {@code ..} or short name. */
    private static boolean same(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().toString()
                    .equalsIgnoreCase(b.toAbsolutePath().normalize().toString());
        }
    }
}
