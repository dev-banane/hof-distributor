package de.devbanane.hof;

import java.nio.file.Path;
import java.util.List;

public final class Launcher {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--dry-run")) {
            dryRun(Path.of(args[1]));
        } else {
            HofDistributorApp.main(args);
        }
    }

    private static void dryRun(Path root) throws Exception {
        List<HofCore.VehicleFolder> buses = HofCore.scanVehicles(root).stream().filter(HofCore.VehicleFolder::isBus).toList();
        List<HofCore.HofEntry> entries = HofCore.collect(buses, HofCore.scanMaps(root));
        System.out.println("Buses: " + buses.stream().map(HofCore.VehicleFolder::name).toList());
        for (HofCore.HofEntry e : entries) {
            System.out.println(e.fileName + " (" + e.variants.size() + " version(s)) -> " + e.chosen.label(root));
        }
        List<HofCore.PlannedCopy> plan = HofCore.plan(entries, buses, false);
        plan.forEach(c -> System.out.println("  " + c.action() + "  " + root.relativize(c.target())));
        System.out.println(plan.size() + " file(s) would be copied.");
    }
}
