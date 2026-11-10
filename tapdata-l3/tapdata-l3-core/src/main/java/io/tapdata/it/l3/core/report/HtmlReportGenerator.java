package io.tapdata.it.l3.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Self-contained interactive HTML dashboard — design doc §8.2.
 * <p>
 * Layout: overview banner (pass-rate ring + stat cards), CDC lag / throughput
 * trend chart (Chart.js), and an expandable Scenario → Step execution waterfall
 * with group filtering and failure stack traces.
 */
public final class HtmlReportGenerator {

    public static final String FILE_NAME = "report.html";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double RING_CIRCUMFERENCE = 2 * Math.PI * 52;

    private static final Map<String, String> METRIC_LABELS = Map.of(
            "cdc_lag_ms", "CDC 延迟 (ms)",
            "replication_lag_ms", "复制延迟 (ms)",
            "throughput_rps", "吞吐量 (rps)",
            "rps", "吞吐量 (rps)",
            "provision_time_ms", "配给耗时 (ms)",
            "elapsed_ms", "执行耗时 (ms)",
            "row_count", "行数",
            "total_rows", "总行数",
            "affected_rows", "影响行数",
            "total_operations", "操作数");

    private HtmlReportGenerator() {
    }

    public static Path write(TestRunReport report, Path outputDir) throws IOException {
        Path file = outputDir.resolve(FILE_NAME);
        Files.writeString(file, toHtml(report), StandardCharsets.UTF_8);
        return file;
    }

    public static String toHtml(TestRunReport report) {
        double passRate = report.passRate();
        boolean allPassed = report.failed() == 0;
        String ringOffset = String.format(Locale.ROOT, "%.2f",
                RING_CIRCUMFERENCE * (1 - passRate / 100.0));

        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>TapData L3 测试报告 · ").append(escape(report.getRunId())).append("</title>\n")
                .append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\"></script>\n")
                .append("<style>").append(CSS).append("</style>\n</head>\n<body>\n<div class=\"page\">\n");

        // ==================== Header ====================
        html.append("<header class=\"hero\">\n<div>\n")
                .append("<span class=\"eyebrow\">TapData L3 · Integration Test Report</span>\n")
                .append("<h1>").append(escape(report.getSuiteName())).append("</h1>\n")
                .append("<p class=\"meta\">").append(escape(report.getRunId()))
                .append(" · ").append(escape(report.getStartTime().toString()))
                .append(" → ").append(escape(report.getEndTime().toString()))
                .append(" · 总耗时 ").append(formatDuration(report.getDurationMs()))
                .append("</p>\n</div>\n")
                .append("<div class=\"hero-status ").append(allPassed ? "ok" : "ko").append("\">")
                .append(allPassed ? "ALL PASSED" : "FAILED")
                .append("</div>\n</header>\n");

        // ==================== Overview ====================
        MetricsSummary metrics = report.getMetricsSummary();
        html.append("<section class=\"overview\">\n")
                .append("<div class=\"card ring-card\">\n")
                .append("<svg viewBox=\"0 0 120 120\" class=\"ring\" aria-label=\"pass rate\">\n")
                .append("<circle class=\"ring-bg\" cx=\"60\" cy=\"60\" r=\"52\"></circle>\n")
                .append("<circle class=\"ring-value\" cx=\"60\" cy=\"60\" r=\"52\" style=\"stroke-dasharray:")
                .append(String.format(Locale.ROOT, "%.2f", RING_CIRCUMFERENCE))
                .append(";stroke-dashoffset:").append(ringOffset).append("\"></circle>\n")
                .append("<text x=\"60\" y=\"58\" text-anchor=\"middle\" class=\"ring-pct\">")
                .append(formatPercent(passRate)).append("</text>\n")
                .append("<text x=\"60\" y=\"78\" text-anchor=\"middle\" class=\"ring-label\">通过率</text>\n")
                .append("</svg>\n")
                .append("<div class=\"ring-caption\">").append(report.passed()).append(" passed · ")
                .append(report.failed()).append(" failed</div>\n</div>\n")
                .append("<div class=\"cards\">\n");

        html.append(statCard("场景总数", String.valueOf(report.total()), "scenario(s)", "neutral"));
        html.append(statCard("通过 / 失败", report.passed() + " / " + report.failed(),
                formatPercent(passRate) + " pass rate", allPassed ? "good" : "bad"));
        html.append(statCard("平均 / 最大 CDC 延迟",
                formatNumber(metrics.getAvgReplicationLagMs()) + " / " + formatNumber(metrics.getMaxReplicationLagMs()) + " ms",
                "metricsSummary", "accent"));
        html.append(statCard("峰值吞吐量",
                formatNumber(metrics.getPeakThroughputRps()) + " rps",
                "dbforge 平均配给 " + formatNumber(metrics.getAvgDbForgeProvisionTimeMs()) + " ms", "accent"));

        html.append("</div>\n</section>\n");

        // ==================== Chart ====================
        List<String> labels = report.getScenarios().stream()
                .map(s -> s.getCaseId() == null ? s.getName() : s.getCaseId())
                .collect(Collectors.toList());
        List<Double> lagSeries = report.getScenarios().stream()
                .map(HtmlReportGenerator::scenarioAvgLag)
                .collect(Collectors.toList());
        List<Double> rpsSeries = report.getScenarios().stream()
                .map(HtmlReportGenerator::scenarioPeakRps)
                .collect(Collectors.toList());

        html.append("<section class=\"panel\">\n")
                .append("<div class=\"panel-head\"><h2>CDC 延迟 / 吞吐量趋势</h2>")
                .append("<span class=\"hint\">指标来自 Step outputs（cdc_lag_ms / throughput_rps）</span></div>\n")
                .append("<div class=\"chart-wrap\"><canvas id=\"metricsChart\"></canvas></div>\n")
                .append("<p id=\"chartFallback\" class=\"chart-fallback\">Chart.js 未加载（可能处于离线环境），请参考下方执行明细中的指标列。</p>\n")
                .append("</section>\n");

        // ==================== Scenarios ====================
        long maxStepDuration = report.getScenarios().stream()
                .flatMap(s -> s.getSteps().stream())
                .mapToLong(StepReport::getDurationMs)
                .max().orElse(1);

        html.append("<section class=\"panel\">\n<div class=\"panel-head\">")
                .append("<h2>执行瀑布 (Scenario → Step)</h2>\n")
                .append("<div class=\"filters\">")
                .append("<button class=\"filter-btn active\" data-group=\"all\">全部</button>\n");

        report.getScenarios().stream().map(ScenarioReport::getGroup).distinct().forEach(group ->
                html.append("<button class=\"filter-btn\" data-group=\"").append(escape(group)).append("\">")
                        .append(escape(group)).append("</button>\n"));
        html.append("</div>\n</div>\n<div class=\"scenarios\">\n");

        for (ScenarioReport scenario : report.getScenarios()) {
            html.append(renderScenario(scenario, maxStepDuration));
        }

        html.append("</div>\n</section>\n")
                .append("<footer>Generated by tapdata-l3 · <span class=\"mono\">")
                .append(escape(report.getRunId())).append("</span></footer>\n</div>\n");

        // ==================== Script ====================
        html.append("<script>\nconst reportData = ")
                .append(chartDataJson(labels, lagSeries, rpsSeries))
                .append(";\n")
                .append(SCRIPT)
                .append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String renderScenario(ScenarioReport scenario, long maxStepDuration) {
        StringBuilder out = new StringBuilder(2048);
        out.append("<details class=\"scenario ").append(scenario.isSuccess() ? "passed" : "failed")
                .append("\" data-group=\"").append(escape(scenario.getGroup())).append("\">\n")
                .append("<summary>\n")
                .append("<span class=\"badge ").append(scenario.isSuccess() ? "ok" : "ko").append("\">")
                .append(scenario.status()).append("</span>\n")
                .append("<span class=\"case-id\">").append(escape(scenario.getCaseId())).append("</span>\n")
                .append("<span class=\"scenario-name\">").append(escape(scenario.getName())).append("</span>\n")
                .append("<span class=\"spacer\"></span>\n")
                .append("<span class=\"chip\">").append(escape(scenario.getGroup())).append("</span>\n")
                .append("<span class=\"duration\">").append(formatDuration(scenario.getDurationMs())).append("</span>\n")
                .append("</summary>\n");

        out.append("<div class=\"steps\">\n");
        if (scenario.getSteps().isEmpty()) {
            out.append("<p class=\"hint\">该场景未执行任何 Step（Preflight 失败）。</p>\n");
        } else {
            out.append("<table>\n<thead><tr>")
                    .append("<th class=\"col-index\">#</th><th>Step ID</th><th>Action</th>")
                    .append("<th>状态</th><th class=\"col-duration\">耗时</th><th>指标</th>")
                    .append("</tr></thead>\n<tbody>\n");

            int index = 0;
            for (StepReport step : scenario.getSteps()) {
                index++;
                long width = Math.max(2, Math.round(step.getDurationMs() * 100.0 / Math.max(1, maxStepDuration)));
                out.append("<tr class=\"").append(step.isSuccess() ? "step-ok" : "step-ko").append("\">\n")
                        .append("<td class=\"col-index\">").append(index).append("</td>\n")
                        .append("<td class=\"mono\">").append(escape(step.getId())).append("</td>\n")
                        .append("<td class=\"mono\">").append(escape(step.getAction())).append("</td>\n")
                        .append("<td><span class=\"badge ").append(step.isSuccess() ? "ok" : "ko").append("\">")
                        .append(step.isSuccess() ? "PASSED" : "FAILED").append("</span></td>\n")
                        .append("<td class=\"col-duration\"><div class=\"bar\"><span style=\"width:")
                        .append(width).append("%\"></span></div>")
                        .append(formatDuration(step.getDurationMs())).append("</td>\n")
                        .append("<td class=\"metrics\">").append(renderMetrics(step.getMetrics())).append("</td>\n")
                        .append("</tr>\n");

                if (!step.isSuccess()) {
                    out.append("<tr class=\"error-row\"><td colspan=\"6\">\n")
                            .append("<div class=\"error-box\"><div class=\"error-title\">")
                            .append(escape(step.getAction())).append(" · ").append(escape(step.getErrorMessage()))
                            .append("</div>");
                    if (step.getStackTrace() != null && !step.getStackTrace().isBlank()) {
                        out.append("<pre>").append(escape(step.getStackTrace())).append("</pre>");
                    }
                    out.append("</div>\n</td></tr>\n");
                }
            }
            out.append("</tbody>\n</table>\n");
        }
        out.append("</div>\n</details>\n");
        return out.toString();
    }

    private static String renderMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "<span class=\"muted\">—</span>";
        }
        return metrics.entrySet().stream()
                .map(entry -> {
                    String label = METRIC_LABELS.getOrDefault(entry.getKey(), entry.getKey());
                    return "<span class=\"metric\">" + escape(label) + "=<strong>"
                            + escape(formatMetricValue(entry.getValue())) + "</strong></span>";
                })
                .collect(Collectors.joining(" "));
    }

    private static String statCard(String label, String value, String sub, String tone) {
        return "<div class=\"card stat " + tone + "\">\n"
                + "<div class=\"stat-label\">" + escape(label) + "</div>\n"
                + "<div class=\"stat-value\">" + escape(value) + "</div>\n"
                + "<div class=\"stat-sub\">" + escape(sub) + "</div>\n</div>\n";
    }

    private static String chartDataJson(List<String> labels, List<Double> lag, List<Double> rps) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("lag", lag);
        data.put("rps", rps);
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"labels\":[],\"lag\":[],\"rps\":[]}";
        }
    }

    private static double scenarioAvgLag(ScenarioReport scenario) {
        List<Double> values = new ArrayList<>();
        for (StepReport step : scenario.getSteps()) {
            for (String key : List.of("cdc_lag_ms", "replication_lag_ms")) {
                Object value = step.getMetrics().get(key);
                if (value instanceof Number number) values.add(number.doubleValue());
            }
        }
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double scenarioPeakRps(ScenarioReport scenario) {
        double peak = 0.0;
        for (StepReport step : scenario.getSteps()) {
            for (String key : List.of("throughput_rps", "rps")) {
                Object value = step.getMetrics().get(key);
                if (value instanceof Number number) peak = Math.max(peak, number.doubleValue());
            }
        }
        return peak;
    }

    private static String formatMetricValue(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d)) return String.format(Locale.ROOT, "%.0f", d);
            return String.format(Locale.ROOT, "%.1f", d);
        }
        return String.valueOf(value);
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
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
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String SCRIPT = """
            (function () {
              const el = document.getElementById('metricsChart');
              if (window.Chart && el) {
                new Chart(el, {
                  type: 'line',
                  data: {
                    labels: reportData.labels,
                    datasets: [
                      {
                        label: 'CDC 延迟 (ms)',
                        data: reportData.lag,
                        borderColor: '#6366f1',
                        backgroundColor: 'rgba(99,102,241,.14)',
                        fill: true,
                        tension: .35,
                        pointRadius: 4,
                        yAxisID: 'y'
                      },
                      {
                        label: '峰值吞吐量 (rps)',
                        data: reportData.rps,
                        borderColor: '#0ea5e9',
                        backgroundColor: 'rgba(14,165,233,.12)',
                        borderDash: [6, 4],
                        tension: .35,
                        pointRadius: 4,
                        yAxisID: 'y1'
                      }
                    ]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'index', intersect: false },
                    plugins: { legend: { labels: { usePointStyle: true, boxWidth: 8 } } },
                    scales: {
                      y: { position: 'left', title: { display: true, text: '延迟 (ms)' }, grid: { color: '#eef1f6' } },
                      y1: { position: 'right', title: { display: true, text: '吞吐量 (rps)' }, grid: { drawOnChartArea: false } },
                      x: { grid: { display: false } }
                    }
                  }
                });
              } else {
                const fallback = document.getElementById('chartFallback');
                if (fallback) fallback.style.display = 'block';
              }

              document.querySelectorAll('.filter-btn').forEach(function (btn) {
                btn.addEventListener('click', function () {
                  document.querySelectorAll('.filter-btn').forEach(function (b) { b.classList.remove('active'); });
                  btn.classList.add('active');
                  const group = btn.getAttribute('data-group');
                  document.querySelectorAll('.scenario').forEach(function (card) {
                    card.style.display = (group === 'all' || card.getAttribute('data-group') === group) ? '' : 'none';
                  });
                });
              });
            })();
            """;

    private static final String CSS = """
            :root {
              --bg: #f4f6fb;
              --card: #ffffff;
              --ink: #0f172a;
              --muted: #64748b;
              --line: #e2e8f0;
              --indigo: #4f46e5;
              --green: #16a34a;
              --red: #dc2626;
              --sky: #0284c7;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; background: var(--bg); color: var(--ink);
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
              -webkit-font-smoothing: antialiased;
            }
            .page { max-width: 1180px; margin: 0 auto; padding: 32px 24px 56px; }
            .hero {
              display: flex; align-items: center; justify-content: space-between; gap: 24px;
              background: linear-gradient(120deg, #111827 0%, #312e81 55%, #4338ca 100%);
              color: #fff; border-radius: 18px; padding: 28px 32px;
              box-shadow: 0 18px 40px rgba(30, 41, 59, .22);
            }
            .eyebrow { font-size: 12px; letter-spacing: .16em; text-transform: uppercase; color: #c7d2fe; }
            .hero h1 { margin: 8px 0 6px; font-size: 26px; font-weight: 650; }
            .meta { margin: 0; color: #cbd5e1; font-size: 13px; }
            .hero-status {
              flex: none; padding: 12px 20px; border-radius: 999px; font-weight: 700; font-size: 13px;
              letter-spacing: .08em; border: 1px solid rgba(255,255,255,.25);
            }
            .hero-status.ok { background: rgba(22, 163, 74, .2); color: #86efac; }
            .hero-status.ko { background: rgba(220, 38, 38, .2); color: #fca5a5; }
            .overview { display: grid; grid-template-columns: 260px 1fr; gap: 18px; margin-top: 18px; }
            .card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; box-shadow: 0 8px 24px rgba(15, 23, 42, .04); }
            .ring-card { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 18px; }
            .ring { width: 168px; height: 168px; transform: rotate(-90deg); }
            .ring circle { fill: none; stroke-width: 12; }
            .ring-bg { stroke: #eef2ff; }
            .ring-value { stroke: var(--indigo); stroke-linecap: round; transition: stroke-dashoffset .8s ease; }
            .ring text { transform: rotate(90deg); transform-origin: 60px 60px; }
            .ring-pct { font-size: 22px; font-weight: 700; fill: var(--ink); }
            .ring-label { font-size: 10px; fill: var(--muted); letter-spacing: .08em; }
            .ring-caption { margin-top: 4px; color: var(--muted); font-size: 13px; }
            .cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
            .stat { padding: 16px 18px; border-left: 4px solid var(--line); }
            .stat.good { border-left-color: var(--green); }
            .stat.bad { border-left-color: var(--red); }
            .stat.accent { border-left-color: var(--indigo); }
            .stat-label { color: var(--muted); font-size: 12px; letter-spacing: .04em; }
            .stat-value { font-size: 22px; font-weight: 650; margin-top: 6px; }
            .stat-sub { color: var(--muted); font-size: 12px; margin-top: 4px; }
            .panel { margin-top: 18px; background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 20px 22px; box-shadow: 0 8px 24px rgba(15, 23, 42, .04); }
            .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
            .panel-head h2 { margin: 0; font-size: 17px; }
            .hint { color: var(--muted); font-size: 12px; }
            .chart-wrap { height: 280px; }
            .chart-fallback { display: none; color: var(--muted); font-size: 13px; }
            .filters { display: flex; gap: 8px; flex-wrap: wrap; }
            .filter-btn {
              border: 1px solid var(--line); background: #fff; color: var(--muted); border-radius: 999px;
              padding: 6px 14px; font-size: 12px; cursor: pointer; transition: all .18s ease;
            }
            .filter-btn:hover { border-color: #c7d2fe; color: var(--indigo); }
            .filter-btn.active { background: #eef2ff; border-color: #c7d2fe; color: var(--indigo); font-weight: 600; }
            .scenarios { display: flex; flex-direction: column; gap: 10px; }
            .scenario { border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: #fbfcfe; }
            .scenario.passed { border-left: 4px solid var(--green); }
            .scenario.failed { border-left: 4px solid var(--red); }
            .scenario summary {
              display: flex; align-items: center; gap: 10px; padding: 13px 16px; cursor: pointer;
              list-style: none; user-select: none;
            }
            .scenario summary::-webkit-details-marker { display: none; }
            .scenario summary::before { content: "▸"; color: var(--muted); font-size: 12px; transition: transform .18s ease; }
            .scenario[open] summary::before { transform: rotate(90deg); }
            .case-id { font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 12px; color: var(--indigo); }
            .scenario-name { font-weight: 600; font-size: 14px; }
            .spacer { flex: 1; }
            .chip { font-size: 11px; color: var(--muted); background: #f1f5f9; border-radius: 999px; padding: 3px 9px; }
            .duration { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
            .badge { display: inline-block; font-size: 10px; font-weight: 700; letter-spacing: .06em; padding: 4px 8px; border-radius: 6px; }
            .badge.ok { color: #166534; background: #dcfce7; }
            .badge.ko { color: #991b1b; background: #fee2e2; }
            .steps { padding: 0 16px 16px; }
            table { width: 100%; border-collapse: collapse; font-size: 13px; background: #fff; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
            th, td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
            th { font-size: 11px; color: var(--muted); font-weight: 600; letter-spacing: .04em; background: #f8fafc; }
            tbody tr:last-child td { border-bottom: none; }
            tr.step-ko td { background: #fff7f7; }
            .col-index { width: 34px; color: var(--muted); }
            .col-duration { width: 180px; font-variant-numeric: tabular-nums; }
            .mono { font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 12px; }
            .bar { height: 6px; background: #eef2ff; border-radius: 999px; margin-bottom: 4px; overflow: hidden; }
            .bar span { display: block; height: 100%; background: linear-gradient(90deg, #6366f1, #0ea5e9); border-radius: 999px; }
            .metrics { color: var(--muted); }
            .metric { display: inline-block; margin: 2px 8px 2px 0; padding: 2px 8px; background: #f8fafc; border: 1px solid var(--line); border-radius: 999px; font-size: 11px; }
            .metric strong { color: var(--ink); }
            .muted { color: #cbd5e1; }
            .error-row td { padding: 0 10px 12px; }
            .error-box { border: 1px solid #fecaca; background: #fef2f2; border-radius: 10px; padding: 12px 14px; }
            .error-title { color: #991b1b; font-weight: 600; font-size: 13px; margin-bottom: 8px; }
            .error-box pre { margin: 0; max-height: 260px; overflow: auto; font-size: 11px; line-height: 1.5; color: #7f1d1d; }
            footer { margin-top: 26px; color: #94a3b8; font-size: 12px; text-align: center; }
            @media (max-width: 860px) {
              .overview { grid-template-columns: 1fr; }
              .cards { grid-template-columns: 1fr; }
              .hero { flex-direction: column; align-items: flex-start; }
            }
            """;
}
