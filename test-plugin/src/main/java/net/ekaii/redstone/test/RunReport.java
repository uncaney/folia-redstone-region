package net.ekaii.redstone.test;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Accumulates test outcomes and emits a JUnit-style XML report. */
public final class RunReport {

    public static final class Case {
        public final String name;
        public final long durationMs;
        public final String failureMessage; // null if passed
        public final String stdout;

        public Case(String name, long durationMs, String failureMessage, String stdout) {
            this.name = name;
            this.durationMs = durationMs;
            this.failureMessage = failureMessage;
            this.stdout = stdout;
        }

        public boolean passed() { return failureMessage == null; }
    }

    private final String suiteName;
    private final List<Case> cases = new ArrayList<>();

    public RunReport(String suiteName) { this.suiteName = suiteName; }

    public synchronized void add(Case c) { cases.add(c); }

    public synchronized int total()    { return cases.size(); }
    public synchronized int failures() { return (int) cases.stream().filter(c -> !c.passed()).count(); }

    public synchronized List<Case> cases() { return List.copyOf(cases); }

    public synchronized void writeJunitXml(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            long totalMs = cases.stream().mapToLong(c -> c.durationMs).sum();
            w.write("<testsuite name=\"" + escape(suiteName) + "\" tests=\"" + cases.size()
                    + "\" failures=\"" + failures() + "\" errors=\"0\" time=\""
                    + (totalMs / 1000.0) + "\">\n");
            for (Case c : cases) {
                w.write("  <testcase classname=\"" + escape(suiteName) + "\" name=\""
                        + escape(c.name) + "\" time=\"" + (c.durationMs / 1000.0) + "\">\n");
                if (!c.passed()) {
                    w.write("    <failure message=\"" + escape(firstLine(c.failureMessage))
                            + "\"><![CDATA[" + cdataSafe(c.failureMessage) + "]]></failure>\n");
                }
                if (c.stdout != null && !c.stdout.isEmpty()) {
                    w.write("    <system-out><![CDATA[" + cdataSafe(c.stdout) + "]]></system-out>\n");
                }
                w.write("  </testcase>\n");
            }
            w.write("</testsuite>\n");
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static String cdataSafe(String s) { return s == null ? "" : s.replace("]]>", "]]]]><![CDATA[>"); }
    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i == -1 ? s : s.substring(0, i);
    }
}
