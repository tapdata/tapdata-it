package io.tapdata.it.l3.core.report;

import io.tapdata.it.l3.core.result.ScenarioResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for report generation: builds the run model from scenario results
 * and writes all three formats required by design doc §8.2.
 */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static TestRunReport writeAll(List<ScenarioResult> results, Path outputDir) throws IOException {
        return writeAll(results, outputDir, ReportBuilder.DEFAULT_SUITE_NAME);
    }

    public static TestRunReport writeAll(List<ScenarioResult> results, Path outputDir,
                                         String suiteName) throws IOException {
        Files.createDirectories(outputDir);
        TestRunReport report = ReportBuilder.build(results, suiteName);
        JsonReportGenerator.write(report, outputDir);
        JUnitXmlReportGenerator.write(report, outputDir);
        HtmlReportGenerator.write(report, outputDir);
        return report;
    }
}
