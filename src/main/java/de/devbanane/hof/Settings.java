package de.devbanane.hof;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Settings {

    private static final String KEY_OMSI = "omsiPath";
    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"(.+?)\"");

    private Settings() {}

    private static Path file() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        return base.resolve("OMSI HOF Distributor").resolve("settings.properties");
    }

    static boolean looksLikeOmsi(Path dir) {
        return dir != null && Files.isDirectory(dir.resolve("Vehicles"));
    }

    static Optional<Path> omsiFolder() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file())) {
            p.load(in);
            String saved = p.getProperty(KEY_OMSI);
            if (saved != null && looksLikeOmsi(Path.of(saved))) return Optional.of(Path.of(saved));
        } catch (IOException | RuntimeException ignored) {
            // no settings yet
        }
        return detectViaSteam();
    }

    static void saveOmsiFolder(Path dir) {
        Properties p = new Properties();
        p.setProperty(KEY_OMSI, dir.toAbsolutePath().toString());
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "OMSI HOF Distributor");
            }
        } catch (IOException ignored) {
            // Remembering the folder is a convenience; never fail the app over it.
        }
    }

    static Optional<Path> detectViaSteam() {
        List<Path> steamRoots = new ArrayList<>();
        registrySteamPath().ifPresent(steamRoots::add);
        steamRoots.add(Path.of("C:\\Program Files (x86)\\Steam"));
        steamRoots.add(Path.of("C:\\Program Files\\Steam"));

        List<Path> libraries = new ArrayList<>();
        for (Path root : steamRoots) {
            libraries.add(root);
            try {
                String vdf = Files.readString(root.resolve("steamapps").resolve("libraryfolders.vdf"), StandardCharsets.UTF_8);
                Matcher m = VDF_PATH.matcher(vdf);
                while (m.find()) libraries.add(Path.of(m.group(1).replace("\\\\", "\\")));
            } catch (IOException | RuntimeException ignored) {
                // not a Steam install
            }
        }
        return libraries.stream()
                .map(lib -> lib.resolve("steamapps").resolve("common").resolve("OMSI 2"))
                .filter(Settings::looksLikeOmsi)
                .findFirst();
    }

    private static Optional<Path> registrySteamPath() {
        try {
            Process proc = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(5, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
            return m.find() ? Optional.of(Path.of(m.group(1).trim())) : Optional.empty();
        } catch (IOException | RuntimeException | InterruptedException e) {
            return Optional.empty();
        }
    }
}
