package de.devbanane.hof;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class HofCore {

    public static final String UNDO_LOG = "hof-distributor-undo.log";
    private static final String BACKUP_SUFFIX = ".hofdist.bak";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private HofCore() {}

    public record VehicleFolder(Path dir, boolean isBus, Map<String, Path> hofs) {
        public String name() { return dir.getFileName().toString(); }
    }

    public record Variant(String hash, long size, FileTime modified, List<Path> locations) {
        boolean fromMap() {
            String sep = java.io.File.separator;
            return locations.stream().anyMatch(p -> p.toString().toLowerCase(Locale.ROOT).contains(sep + "maps" + sep));
        }

        public String label(Path root) {
            String where = String.join(", ", locations.stream()
                    .map(p -> root.relativize(p.getParent()).toString()).toList());
            String date = LocalDateTime.ofInstant(modified.toInstant(), ZoneId.systemDefault()).format(DATE);
            return "%s  %,d B  %s  (%s)".formatted(hash.substring(0, 6), size, date, where);
        }
    }

    public static final class HofEntry {
        public final String fileName;
        public final List<Variant> variants;
        public Variant chosen;

        HofEntry(String fileName, List<Variant> variants) {
            this.fileName = fileName;
            this.variants = variants;
            this.chosen = variants.getFirst();
        }

        public String key() { return fileName.toLowerCase(Locale.ROOT); }
    }

    public enum Action { COPY, OVERWRITE }

    public record PlannedCopy(Action action, HofEntry entry, VehicleFolder vehicle) {
        public Path target() {
            Path existing = vehicle.hofs().get(entry.key());
            return existing != null ? existing : vehicle.dir().resolve(entry.fileName);
        }
    }

    public static List<VehicleFolder> scanVehicles(Path omsiRoot) throws IOException {
        Path vehicles = omsiRoot.resolve("Vehicles");
        if (!Files.isDirectory(vehicles)) {
            throw new IOException("No 'Vehicles' folder in " + omsiRoot);
        }
        List<VehicleFolder> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(vehicles)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted(byName()).toList()) {
                boolean isBus = false;
                Map<String, Path> hofs = new TreeMap<>();
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (n.endsWith(".bus")) isBus = true;
                        if (n.endsWith(".hof")) hofs.put(n, f);
                    }
                }
                result.add(new VehicleFolder(dir, isBus, hofs));
            }
        }
        return result;
    }

    public static List<Path> scanMaps(Path omsiRoot) throws IOException {
        Path maps = omsiRoot.resolve("maps");
        if (!Files.isDirectory(maps)) return List.of();
        try (Stream<Path> s = Files.walk(maps)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hof"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    public static List<HofEntry> collect(List<VehicleFolder> sources, List<Path> mapHofs) throws IOException {
        List<Path> all = new ArrayList<>(mapHofs);
        sources.forEach(v -> all.addAll(v.hofs().values()));

        Map<String, String> displayName = new TreeMap<>();
        Map<String, Map<String, List<Path>>> byNameThenHash = new TreeMap<>();
        for (Path p : all) {
            String name = p.getFileName().toString();
            String key = name.toLowerCase(Locale.ROOT);
            displayName.putIfAbsent(key, name);
            byNameThenHash.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(hash(p), h -> new ArrayList<>())
                    .add(p);
        }

        List<HofEntry> entries = new ArrayList<>();
        for (var e : byNameThenHash.entrySet()) {
            List<Variant> variants = new ArrayList<>();
            for (var v : e.getValue().entrySet()) {
                List<Path> locs = v.getValue();
                FileTime newest = locs.stream().map(HofCore::mtime).max(Comparator.naturalOrder()).orElseThrow();
                variants.add(new Variant(v.getKey(), Files.size(locs.getFirst()), newest, List.copyOf(locs)));
            }
            variants.sort(Comparator.comparing(Variant::fromMap)
                    .thenComparingInt(v -> v.locations().size())
                    .thenComparing(Variant::modified)
                    .thenComparingLong(Variant::size)
                    .reversed());
            entries.add(new HofEntry(displayName.get(e.getKey()), variants));
        }
        return entries;
    }

    public static List<PlannedCopy> plan(List<HofEntry> entries, List<VehicleFolder> targets, boolean overwrite) {
        List<PlannedCopy> plan = new ArrayList<>();
        for (VehicleFolder v : targets) {
            for (HofEntry e : entries) {
                Path existing = v.hofs().get(e.key());
                if (existing == null) {
                    plan.add(new PlannedCopy(Action.COPY, e, v));
                } else if (overwrite && !e.chosen.locations().contains(existing)
                        && !hashUnchecked(existing).equals(e.chosen.hash())) {
                    plan.add(new PlannedCopy(Action.OVERWRITE, e, v));
                }
            }
        }
        return plan;
    }

    public static int apply(List<PlannedCopy> plan, Path omsiRoot) throws IOException {
        List<String> log = new ArrayList<>();
        log.add("# hof-distributor run " + LocalDateTime.now());
        int done = 0;
        try {
            for (PlannedCopy c : plan) {
                Path src = c.entry().chosen.locations().getFirst();
                Path target = c.target();
                if (c.action() == Action.OVERWRITE) {
                    Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    log.add("OVERWROTE\t" + target + "\t" + backup);
                    Files.copy(src, target, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.copy(src, target, StandardCopyOption.COPY_ATTRIBUTES);
                    log.add("CREATED\t" + target);
                }
                done++;
            }
        } finally {
            Files.write(omsiRoot.resolve(UNDO_LOG), log, StandardCharsets.UTF_8);
        }
        return done;
    }

    public static boolean hasUndo(Path omsiRoot) {
        return Files.isRegularFile(omsiRoot.resolve(UNDO_LOG));
    }

    public static int undo(Path omsiRoot) throws IOException {
        Path logFile = omsiRoot.resolve(UNDO_LOG);
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        int reverted = 0;
        for (String line : lines.reversed()) {
            String[] parts = line.split("\t");
            switch (parts[0]) {
                case "CREATED" -> {
                    if (Files.deleteIfExists(Path.of(parts[1]))) reverted++;
                }
                case "OVERWROTE" -> {
                    Path backup = Path.of(parts[2]);
                    if (Files.exists(backup)) {
                        Files.move(backup, Path.of(parts[1]), StandardCopyOption.REPLACE_EXISTING);
                        reverted++;
                    }
                }
                default -> { }
            }
        }
        Files.delete(logFile);
        return reverted;
    }

    private static Comparator<Path> byName() {
        return Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static FileTime mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String hashUnchecked(Path p) {
        try {
            return hash(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String hash(Path p) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
