package io.tapdata.it.l3.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.core.result.ScenarioResult;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonJUnitAndHtmlReports() throws Exception {
        ScenarioConfig passed = ScenarioConfig.builder()
                .caseId("L3-F-01")
                .scenario("MySQL to PostgreSQL CDC Sync")
                .group("functional")
                .build();
        ScenarioConfig failed = ScenarioConfig.builder()
                .caseId("L3-S-01")
                .scenario("Failover Recovery")
                .group("stability")
                .build();

        StepConfig createDb = StepConfig.builder().id("create_src_db").action("dbforge_create_database").build();
        StepConfig assertStep = StepConfig.builder().id("assert_data_sync").action("assert_jdbc_data").build();
        StepConfig recovered = StepConfig.builder().id("wait_recovery").action("tm_wait_task").build();

        StepResult createDbResult = StepResult.success(createDb, 1200,
                Map.of("provision_time_ms", 1250));
        StepResult assertResult = StepResult.success(assertStep, 2300,
                Map.of("cdc_lag_ms", 310, "throughput_rps", 1200));
        StepResult failedStep = StepResult.failure(recovered,
                new IllegalStateException("Task did not recover within 120s"), 5000);

        Instant start = Instant.parse("2026-11-10T10:00:00Z");
        Instant end = Instant.parse("2026-11-10T10:02:30Z");

        List<ScenarioResult> results = List.of(
                ScenarioResult.success(passed, 18_500, start, end, List.of(createDbResult, assertResult)),
                ScenarioResult.failed(failed, failedStep, 9_000, start, end, List.of(failedStep)));

        TestRunReport report = ReportWriter.writeAll(results, tempDir);

        assertEquals(2, report.total());
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
        assertEquals(50.0, report.passRate(), 0.001);

        Path json = tempDir.resolve(JsonReportGenerator.FILE_NAME);
        Path xml = tempDir.resolve(JUnitXmlReportGenerator.FILE_NAME);
        Path html = tempDir.resolve(HtmlReportGenerator.FILE_NAME);
        assertTrue(Files.exists(json));
        assertTrue(Files.exists(xml));
        assertTrue(Files.exists(html));

        JsonNode root = new ObjectMapper().readTree(Files.readString(json));
        assertEquals(2, root.path("summary").path("total").asInt());
        assertEquals(50.0, root.path("summary").path("passRate").asDouble(), 0.001);
        assertEquals(310.0, root.path("metricsSummary").path("avgReplicationLagMs").asDouble(), 0.001);
        assertEquals(1200.0, root.path("metricsSummary").path("peakThroughputRps").asDouble(), 0.001);
        assertEquals(1250.0, root.path("metricsSummary").path("avgDbForgeProvisionTimeMs").asDouble(), 0.001);
        assertEquals("FAILED", root.path("scenarios").get(1).path("status").asText());

        String xmlContent = Files.readString(xml);
        assertTrue(xmlContent.contains("<testsuites"));
        assertTrue(xmlContent.contains("L3-Functional"));
        assertTrue(xmlContent.contains("L3-S-01: Failover Recovery"));
        assertTrue(xmlContent.contains("<failure message=\"Task did not recover within 120s\">"));

        String htmlContent = Files.readString(html);
        assertTrue(htmlContent.contains("TapData L3 Integration Test Suite"));
        assertTrue(htmlContent.contains("L3-F-01"));
        assertTrue(htmlContent.contains("L3-S-01"));
        assertTrue(htmlContent.contains("Task did not recover within 120s"));
    }
}
