package net.dedale.redstone.test;

import net.dedale.redstone.region.ac.AcRedstoneWireEvaluator;
import net.dedale.redstone.region.nms.EvaluatorSwap;
import net.dedale.redstone.test.contraptions.Contraption;
import net.dedale.redstone.test.contraptions.Contraptions;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestPluginMain extends JavaPlugin {

    private static final long INITIAL_DELAY_TICKS = 100; // give worlds time to load
    private static final Path RESULTS_DIR = Path.of("test-results");

    @Override
    public void onEnable() {
        // Schedule the test driver on the global region scheduler — its body
        // dispatches per-region work via RegionScheduler.execute.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, $ -> startRun(), INITIAL_DELAY_TICKS);
        getLogger().info("test-plugin scheduled: starting run in " + INITIAL_DELAY_TICKS + " ticks");
    }

    private void startRun() {
        World w = pickTestWorld();
        if (w == null) {
            failHarness("no test world available");
            return;
        }
        getLogger().info("running parity tests in world: " + w.getName());
        ParityRunner runner = new ParityRunner(this, w, getLogger());
        RunReport report = new RunReport("folia-redstone-region");

        // Smoke checks: must run BEFORE we hand control to per-region tasks.
        long acAddedBefore = AcRedstoneWireEvaluator.statValue("added");
        long acUpdatedBefore = AcRedstoneWireEvaluator.statValue("updated");
        long acRemovedBefore = AcRedstoneWireEvaluator.statValue("removed");
        report.add(installCheckCase());

        List<Contraption> all = Contraptions.all();
        AtomicInteger remaining = new AtomicInteger(all.size() + 4); // +perf +persistence +stress +cross-region

        for (int i = 0; i < all.size(); i++) {
            int laneIndex = i;
            Contraption c = all.get(i);
            CompletableFuture<RunReport.Case> fut = runner.run(c, laneIndex);
            fut.whenComplete((rc, err) -> {
                handleResult(report, c.id(), rc, err);
                if (remaining.decrementAndGet() == 0) {
                    addAcWasExercisedCase(report, acAddedBefore, acUpdatedBefore, acRemovedBefore);
                    finalizeRun(report);
                }
            });
        }

        Runnable maybeFinalize = () -> {
            if (remaining.decrementAndGet() == 0) {
                addAcWasExercisedCase(report, acAddedBefore, acUpdatedBefore, acRemovedBefore);
                finalizeRun(report);
            }
        };

        // Perf benchmark — independent zone (x=5000+), runs concurrently.
        new PerfRunner(this, w).run().whenComplete((rc, err) -> {
            handleResult(report, "perf-32x32-grid", rc, err);
            maybeFinalize.run();
        });

        // Persistence test (PDC roundtrip).
        new PersistenceRunner(this, w).run().whenComplete((rc, err) -> {
            handleResult(report, "persistence-pdc", rc, err);
            maybeFinalize.run();
        });

        // Stress (long activity, watch for thread-safety errors).
        new StressRunner(this, w).run().whenComplete((rc, err) -> {
            handleResult(report, "stress-600t", rc, err);
            maybeFinalize.run();
        });

        // Cross-region stress: two AC zones in DIFFERENT Folia regions, ticking
        // simultaneously — exercises the per-thread WireHandler thread-safety design.
        new CrossRegionStressRunner(this, w).run().whenComplete((rc, err) -> {
            handleResult(report, "cross-region-stress", rc, err);
            maybeFinalize.run();
        });
    }

    private void handleResult(RunReport report, String label, RunReport.Case rc, Throwable err) {
        if (err != null) {
            report.add(new RunReport.Case(label, 0, "exception: " + err, err.toString()));
        } else if (rc != null) {
            report.add(rc);
        }
        getLogger().info("[" + label + "] " + (rc != null && rc.passed() ? "PASS" : "FAIL"));
    }

    private RunReport.Case installCheckCase() {
        boolean installed = EvaluatorSwap.isInstalled();
        return new RunReport.Case("evaluator-installed", 0,
                installed ? null : "EvaluatorSwap.isInstalled() == false at test-start; bootstrap probably failed",
                "installed=" + installed);
    }

    private void addAcWasExercisedCase(RunReport report, long ab, long ub, long rb) {
        long aa = AcRedstoneWireEvaluator.statValue("added");
        long ua = AcRedstoneWireEvaluator.statValue("updated");
        long ra = AcRedstoneWireEvaluator.statValue("removed");
        long deltaAdded   = aa - ab;
        long deltaUpdated = ua - ub;
        long deltaRemoved = ra - rb;
        long total = deltaAdded + deltaUpdated + deltaRemoved;
        String stdout = "ac added(+" + deltaAdded + ") updated(+" + deltaUpdated
                + ") removed(+" + deltaRemoved + ") total=" + total;
        if (total < 50) {
            report.add(new RunReport.Case("ac-actually-exercised", 0,
                    "AC dispatcher counters didn't move enough (" + total + ") — "
                            + "test results may be a vanilla-vanilla coincidence",
                    stdout));
        } else {
            report.add(new RunReport.Case("ac-actually-exercised", 0, null, stdout));
        }
        getLogger().info("[ac-actually-exercised] " + stdout);
    }

    private void finalizeRun(RunReport report) {
        try {
            Files.createDirectories(RESULTS_DIR);
            Path xml = RESULTS_DIR.resolve("junit.xml");
            report.writeJunitXml(xml);
            getLogger().info("wrote " + xml.toAbsolutePath() + " (cases=" + report.total()
                    + ", failures=" + report.failures() + ")");
            // Marker file used by test-harness/docker-compose healthcheck
            Files.writeString(RESULTS_DIR.resolve("ready"),
                    "cases=" + report.total() + " failures=" + report.failures() + "\n");
        } catch (Throwable t) {
            getLogger().severe("could not write test results: " + t);
        }
    }

    private World pickTestWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (!w.getName().endsWith("_nether") && !w.getName().endsWith("_the_end")) return w;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    private void failHarness(String reason) {
        getLogger().severe(reason);
        try {
            Files.createDirectories(RESULTS_DIR);
            Files.writeString(RESULTS_DIR.resolve("ready"), "ABORTED: " + reason + "\n");
        } catch (Throwable ignored) {}
    }
}
