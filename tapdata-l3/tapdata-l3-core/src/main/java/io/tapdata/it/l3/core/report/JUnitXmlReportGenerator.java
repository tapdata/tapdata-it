package io.tapdata.it.l3.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JUnit XML report for native rendering in GitHub Actions / any CI — design doc §8.2.
 * <p>
 * One {@code <testsuite>} is emitted per scenario group so that UI grouping stays
 * meaningful (functional / destructive / stability).
 */
public final class JUnitXmlReportGenerator {

    public static final String FILE_NAME = "junit-report.xml";

    private JUnitXmlReportGenerator() {
    }

    public static Path write(TestRunReport report, Path outputDir) throws IOException {
        Path file = outputDir.resolve(FILE_NAME);
        Files.writeString(file, toXml(report), StandardCharsets.UTF_8);
        return file;
    }

    public static String toXml(TestRunReport report) {
        Map<String, List<ScenarioReport>> byGroup = report.getScenarios().stream()
                .collect(Collectors.groupingBy(ScenarioReport::getGroup, LinkedHashMap::new, Collectors.toList()));

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuites name=\"").append(escape(report.getSuiteName())).append("\">\n");

        for (Map.Entry<String, List<ScenarioReport>> entry : byGroup.entrySet()) {
            List<ScenarioReport> scenarios = entry.getValue();
            int failures = (int) scenarios.stream().filter(s -> !s.isSuccess()).count();
            double totalSeconds = scenarios.stream().mapToLong(ScenarioReport::getDurationMs).sum() / 1000.0;

            xml.append("  <testsuite name=\"").append(escape(suiteName(entry.getKey()))).append("\"")
                    .append(" tests=\"").append(scenarios.size()).append("\"")
                    .append(" failures=\"").append(failures).append("\"")
                    .append(" errors=\"0\"")
                    .append(" skipped=\"0\"")
                    .append(" time=\"").append(seconds(totalSeconds)).append("\">\n");

            for (ScenarioReport scenario : scenarios) {
                xml.append("    <testcase name=\"").append(escape(testCaseName(scenario))).append("\"")
                        .append(" classname=\"tapdata-l3\"")
                        .append(" time=\"").append(seconds(scenario.getDurationMs() / 1000.0)).append("\"");
                if (scenario.isSuccess()) {
                    xml.append("/>\n");
                } else {
                    String message = failureMessage(scenario);
                    xml.append(">\n");
                    xml.append("      <failure message=\"").append(escape(message)).append("\">")
                            .append(escape(failureDetail(scenario)))
                            .append("</failure>\n");
                    xml.append("    </testcase>\n");
                }
            }

            xml.append("  </testsuite>\n");
        }

        xml.append("</testsuites>\n");
        return xml.toString();
    }

    private static String suiteName(String group) {
        if (group == null || group.isBlank()) {
            return "L3-Ungrouped";
        }
        return "L3-" + group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    private static String testCaseName(ScenarioReport scenario) {
        String caseId = scenario.getCaseId() == null ? "" : scenario.getCaseId();
        String name = scenario.getName() == null ? "" : scenario.getName();
        return caseId.isBlank() ? name : caseId + ": " + name;
    }

    private static String failureMessage(ScenarioReport scenario) {
        return scenario.getSteps().stream()
                .filter(step -> !step.isSuccess())
                .map(StepReport::getErrorMessage)
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse("Scenario failed");
    }

    private static String failureDetail(ScenarioReport scenario) {
        return scenario.getSteps().stream()
                .filter(step -> !step.isSuccess())
                .map(step -> {
                    String stack = step.getStackTrace();
                    return stack == null || stack.isBlank()
                            ? step.getAction() + ": " + step.getErrorMessage()
                            : stack;
                })
                .findFirst()
                .orElse("Scenario failed");
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // Strip characters that are illegal in XML 1.0
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
