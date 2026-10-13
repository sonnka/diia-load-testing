package ua.gov.diia.loadtest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.DurationUnit

internal fun Duration.formatMillis(): String = String.format(Locale.ROOT, "%.1f", toDouble(DurationUnit.MILLISECONDS))

internal fun Duration.millis(): Double = toDouble(DurationUnit.MILLISECONDS)

internal fun percent(rate: Double): String = String.format(Locale.ROOT, "%.2f%%", rate * 100)

public object ConsoleReport {
    @JvmStatic
    public fun printProgress(progress: Progress) {
        println(
            String.format(
                Locale.ROOT,
                "[%5.0fs] %-8s target=%7.1f/s arrivals=%7.1f/s ok=%7.1f/s ko=%6.1f/s p99=%7sms in-flight=%d dropped=%d",
                progress.elapsed.toDouble(DurationUnit.SECONDS),
                progress.phase,
                progress.targetRate,
                progress.arrivalsPerSecond,
                progress.okPerSecond,
                progress.koPerSecond,
                progress.p99.formatMillis(),
                progress.inFlight,
                progress.droppedTotal,
            ),
        )
    }

    @JvmStatic
    @JvmOverloads
    public fun print(
        result: LoadTestResult,
        verdict: Verdict? = null,
    ) {
        println(render(result, verdict))
    }

    @JvmStatic
    @JvmOverloads
    public fun render(
        result: LoadTestResult,
        verdict: Verdict? = null,
    ): String =
        buildString {
            val rule = "=".repeat(100)
            val width = maxOf(20, result.steps.maxOfOrNull { it.length } ?: 0)
            appendLine(rule)
            appendLine("Scenario: ${result.scenario}")
            appendLine(
                "Started:  ${result.startedAt}   elapsed: ${result.elapsed.toDouble(
                    DurationUnit.SECONDS,
                ).let { "%.1f".format(Locale.ROOT, it) }}s",
            )
            appendLine("Profile:  ${result.profile}")
            appendLine("Latencies: every completed call, successful or failed")

            fun table(
                title: String,
                phase: String?,
            ) {
                appendLine("-".repeat(100))
                appendLine(title)
                appendLine(
                    String.format(
                        Locale.ROOT,
                        "  %-${width}s %9s %7s %8s %9s %9s %9s %9s",
                        "step",
                        "ok",
                        "ko",
                        "err",
                        "p50 ms",
                        "p95 ms",
                        "p99 ms",
                        "max ms",
                    ),
                )
                (result.steps.map { it to result.stats(phase, it) } + ("all steps" to result.stats(phase)))
                    .filter { it.second.total > 0 }
                    .forEach { (name, s) ->
                        appendLine(
                            String.format(
                                Locale.ROOT,
                                "  %-${width}s %9d %7d %8s %9s %9s %9s %9s",
                                name,
                                s.ok,
                                s.ko,
                                percent(s.errorRate),
                                s.latency.p50.formatMillis(),
                                s.latency.p95.formatMillis(),
                                s.latency.p99.formatMillis(),
                                s.latency.max.formatMillis(),
                            ),
                        )
                    }
                result.steps.forEach { step ->
                    val s = result.stats(phase, step)
                    if (s.ko > 0) {
                        val failedLatency =
                            s.failureLatency.takeIf { it.count > 0 }?.let {
                                " (failed calls took p50 ${it.p50.formatMillis()} / max ${it.max.formatMillis()} ms)"
                            } ?: ""
                        appendLine("  failures in $step$failedLatency:")
                        s.failureDetails.forEach { appendLine("    ${it.count} x ${it.code}: ${it.message}") }
                    }
                }
                val runDuration = result.runDuration(phase)
                appendLine(
                    "  runs: ${result.runs(phase)} (failed ${result.failedRuns(phase)})   whole run p50/p95/p99/max: " +
                        listOf(
                            runDuration.p50,
                            runDuration.p95,
                            runDuration.p99,
                            runDuration.max,
                        ).joinToString(" / ") { it.formatMillis() } +
                        " ms",
                )
                val lag = result.clientLag(phase)
                val dropped = if (phase == null) result.totalDropped else result.dropped[phase] ?: 0L
                appendLine(
                    "  client start lag p99/max: ${lag.p99.formatMillis()} / ${lag.max.formatMillis()} ms   dropped arrivals: $dropped",
                )
            }

            result.phases.forEach { phase -> table("Phase '$phase'", phase) }
            if (result.phases.size > 1) table("All phases", null)
            appendLine("-".repeat(100))
            if (result.abandoned > 0) appendLine("Runs abandoned at drain timeout: ${result.abandoned}")
            if (verdict != null) {
                appendLine("Assertions: ${if (verdict.passed) "PASSED" else "FAILED"}")
                verdict.results.forEach { appendLine("  $it") }
            }
            append(rule)
        }
}

public object JsonSummary {
    @JvmStatic
    @JvmOverloads
    public fun render(
        result: LoadTestResult,
        verdict: Verdict? = null,
    ): JsonObject =
        buildJsonObject {
            put("scenario", result.scenario)
            put("startedAt", result.startedAt.toString())
            put("elapsedSeconds", result.elapsed.toDouble(DurationUnit.SECONDS))
            verdict?.let { put("passed", it.passed) }
            putJsonArray("stages") {
                result.profile.stages.forEach { stage ->
                    addJsonObject {
                        put("phase", stage.phase)
                        put("fromRate", stage.fromRate)
                        put("toRate", stage.toRate)
                        put("durationSeconds", stage.duration.toDouble(DurationUnit.SECONDS))
                    }
                }
            }
            putJsonObject("phases") {
                (result.phases.map { it as String? } + null).forEach { phase ->
                    putJsonObject(phase ?: "all") {
                        putJsonObject("steps") {
                            result.steps.forEach { step -> put(step, statsJson(result.stats(phase, step))) }
                        }
                        put("allSteps", statsJson(result.stats(phase)))
                        put("runs", result.runs(phase))
                        put("failedRuns", result.failedRuns(phase))
                        putJsonObject("runDuration") {
                            val d = result.runDuration(phase)
                            put("p50Ms", d.p50.millis())
                            put("p95Ms", d.p95.millis())
                            put("p99Ms", d.p99.millis())
                            put("maxMs", d.max.millis())
                        }
                        put("clientLagP99Ms", result.clientLag(phase).p99.millis())
                        put("dropped", if (phase == null) result.totalDropped else result.dropped[phase] ?: 0L)
                    }
                }
            }
            put("abandoned", result.abandoned)
            verdict?.let { v ->
                putJsonArray("assertions") {
                    v.results.forEach {
                        addJsonObject {
                            put("description", it.description)
                            put("passed", it.passed)
                            put("actual", it.actual)
                        }
                    }
                }
            }
        }

    private fun statsJson(stats: StepStats): JsonObject =
        buildJsonObject {
            put("ok", stats.ok)
            put("ko", stats.ko)
            put("errorRate", stats.errorRate)
            put("p50Ms", stats.latency.p50.millis())
            put("p95Ms", stats.latency.p95.millis())
            put("p99Ms", stats.latency.p99.millis())
            put("maxMs", stats.latency.max.millis())
            put("meanMs", stats.latency.mean.millis())
            put("successP99Ms", stats.successLatency.p99.millis())
            put("failedP99Ms", stats.failureLatency.p99.millis())
            put("failedMaxMs", stats.failureLatency.max.millis())
            putJsonObject("failures") { stats.failures.forEach { (code, count) -> put(code, count) } }
            putJsonArray("failureDetails") {
                stats.failureDetails.forEach {
                    addJsonObject {
                        put("code", it.code)
                        put("message", it.message)
                        put("count", it.count)
                    }
                }
            }
        }
}

public object HtmlReport {
    /**
     * A self-contained page: per-phase summary tables plus per-second latency and throughput charts
     * for all steps together and for each step. Its size grows with run duration, not request count.
     * Charts use Chart.js from the jsDelivr CDN, so viewing (not generating) needs network access.
     */
    @JvmStatic
    @JvmOverloads
    public fun render(
        result: LoadTestResult,
        title: String = result.scenario,
        verdict: Verdict? = null,
    ): String {
        val tabs: List<Pair<String, String?>> = listOf("All steps" to null) + result.steps.map { it to it }
        val data: JsonArray =
            buildJsonArray {
                tabs.forEach { (_, step) ->
                    addJsonObject {
                        putJsonArray("points") {
                            result.timeline.filter { it.step == step }.forEach { p ->
                                addJsonObject {
                                    put("t", p.second)
                                    put("ok", p.ok)
                                    put("ko", p.ko)
                                    // A second in which no call completed has no latency: null leaves a gap
                                    // in the chart instead of a misleading drop to 0 ms.
                                    val measured = p.timed > 0
                                    put("p50", if (measured) p.p50.millis() else null)
                                    put("p95", if (measured) p.p95.millis() else null)
                                    put("p99", if (measured) p.p99.millis() else null)
                                    put("max", if (measured) p.max.millis() else null)
                                }
                            }
                        }
                    }
                }
            }

        val buttons =
            tabs.indices.joinToString("\n") { i ->
                """<button class="tab${if (i == 0) " active" else ""}" id="tab_$i" onclick="show($i)">${escape(tabs[i].first)}</button>"""
            }
        val panels =
            tabs.indices.joinToString("\n") { i ->
                """
                <section class="panel" id="panel_$i" style="display:${if (i == 0) "block" else "none"}">
                  ${summaryTable(result, tabs[i].second)}
                  <h3>Latency of completed calls (successful and failed), per second (ms)</h3>
                  <p style="color:#8b949e;margin:0 0 8px">A point is the calls that completed in that second. Gaps: no call
                  completed.</p>
                  <canvas id="latency_$i" height="90"></canvas>
                  <h3>Throughput, per second</h3><canvas id="throughput_$i" height="90"></canvas>
                </section>
                """.trimIndent()
            }
        val verdictHtml =
            verdict?.let { v ->
                "<h2 class=\"${if (v.passed) "pass" else "fail"}\">Assertions ${if (v.passed) "passed" else "failed"}</h2><ul>" +
                    v.results.joinToString("") {
                        "<li class=\"${if (it.passed) "pass" else "fail"}\">${escape(it.description)} " +
                            "<span class=\"muted\">(actual: ${escape(it.actual)})</span></li>"
                    } + "</ul>"
            } ?: ""
        val stagesHtml = result.profile.stages.joinToString("") { "<li>${escape(it.toString())}</li>" }
        val dataJson = data.toString().replace("</", "<\\/")
        val meta =
            "scenario: ${escape(result.scenario)} · started ${result.startedAt} · " +
                "elapsed ${result.elapsed.toDouble(DurationUnit.SECONDS).toInt()}s · " +
                "dropped ${result.totalDropped} · abandoned ${result.abandoned}"

        val page =
            """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
            <style>
              body { font-family: system-ui, -apple-system, sans-serif; margin: 2rem; background: #0d1117; color: #e6edf3; }
              h1 { font-size: 1.4rem; } h2 { font-size: 1.1rem; } h3 { font-size: .95rem; color: #9ca3af; margin: 1.5rem 0 .4rem; }
              table { border-collapse: collapse; margin: 1rem 0; } td, th { padding: .3rem .8rem; border: 1px solid #30363d; }
              th { text-align: left; } td { text-align: right; font-variant-numeric: tabular-nums; }
              .muted { color: #9ca3af; } .pass { color: #3fb950; } .fail { color: #f85149; }
              td.text { text-align: left; white-space: nowrap; } td.msg { white-space: normal; max-width: 40rem; overflow-wrap: anywhere; }
              .tabs { display: flex; flex-wrap: wrap; gap: .25rem; border-bottom: 1px solid #30363d; margin-top: 1.5rem; }
              .tab { font: inherit; color: #9ca3af; background: none; border: none; cursor: pointer; padding: .6rem 1.1rem; border-bottom: 2px solid transparent; }
              .tab:hover, .tab.active { color: #e6edf3; } .tab.active { border-bottom-color: #3fb950; }
              .panel { max-width: 1000px; } canvas { max-width: 100%; }
            </style>
            </head>
            <body>
            <h1>${escape(title)}</h1>
            <p class="muted">$meta</p>
            <ul class="muted">$stagesHtml</ul>
            $verdictHtml
            <div class="tabs">
            $buttons
            </div>
            $panels
            <script>
              const tabs = $dataJson;
              const axis = '#e6edf3', grid = '#30363d';
              const rendered = new Set();
              const scales = (stacked, yTitle) => ({
                x: { stacked, title: { display: true, text: 'elapsed (s)', color: axis }, ticks: { color: axis }, grid: { color: grid } },
                y: { stacked, title: { display: !!yTitle, text: yTitle, color: axis }, ticks: { color: axis }, grid: { color: grid } },
              });
              function render(i) {
                if (rendered.has(i)) return;
                rendered.add(i);
                const p = tabs[i].points, labels = p.map(b => b.t);
                const legend = { legend: { labels: { color: axis } } };
                new Chart(document.getElementById('latency_' + i), {
                  type: 'line',
                  data: { labels, datasets: [
                    { label: 'p50', data: p.map(b => b.p50), borderColor: '#3fb950', pointRadius: 0 },
                    { label: 'p95', data: p.map(b => b.p95), borderColor: '#d29922', pointRadius: 0 },
                    { label: 'p99', data: p.map(b => b.p99), borderColor: '#db6d28', pointRadius: 0 },
                    { label: 'max', data: p.map(b => b.max), borderColor: '#f85149', pointRadius: 0 },
                  ] },
                  // hovering a second shows all four values, also where the lines lie on top of each other
                  options: { scales: scales(false, 'ms'), plugins: legend, interaction: { mode: 'index', intersect: false } },
                });
                new Chart(document.getElementById('throughput_' + i), {
                  type: 'bar',
                  data: { labels, datasets: [
                    { label: 'OK/s', data: p.map(b => b.ok), backgroundColor: '#3fb950' },
                    { label: 'KO/s', data: p.map(b => b.ko), backgroundColor: '#f85149' },
                  ] },
                  options: { scales: scales(true, ''), plugins: legend },
                });
              }
              function show(i) {
                document.querySelectorAll('.panel').forEach(el => { el.style.display = 'none'; });
                document.querySelectorAll('.tab').forEach(el => { el.classList.remove('active'); });
                document.getElementById('panel_' + i).style.display = 'block';
                document.getElementById('tab_' + i).classList.add('active');
                render(i);
              }
              render(0);
            </script>
            </body>
            </html>
            """.trimIndent()
        return page
    }

    private fun summaryTable(
        result: LoadTestResult,
        step: String?,
    ): String {
        val rows =
            (result.phases.map { it as String? } + null).joinToString("\n") { phase ->
                val s = result.stats(phase, step)
                "<tr><th>${escape(phase ?: "all phases")}</th><td>${s.ok}</td><td>${s.ko}</td><td>${percent(s.errorRate)}</td>" +
                    "<td>${s.latency.p50.formatMillis()}</td><td>${s.latency.p95.formatMillis()}</td>" +
                    "<td>${s.latency.p99.formatMillis()}</td><td>${s.latency.max.formatMillis()}</td></tr>"
            }
        val steps =
            "<table><tr><th>phase</th><th>ok</th><th>ko</th><th>errors</th><th>p50 ms</th><th>p95 ms</th>" +
                "<th>p99 ms</th><th>max ms</th></tr>$rows</table>" + failuresTable(result, step)
        return if (step == null) steps + runsTable(result) else steps
    }

    /**
     * What failed: one row per step, code and message, with the count in each phase. The message is the
     * error's own text and its root cause, e.g. `io exception <- Connection reset by peer`.
     */
    private fun failuresTable(
        result: LoadTestResult,
        step: String?,
    ): String {
        val all = result.stats(null, step)
        if (all.ko == 0L) return ""
        val steps = if (step == null) result.steps else listOf(step)
        val rows =
            steps.flatMap { name ->
                result.stats(null, name).failureDetails.map { detail ->
                    val perPhase =
                        result.phases.joinToString("") { phase ->
                            val count =
                                result
                                    .stats(phase, name)
                                    .failureDetails
                                    .firstOrNull { it.code == detail.code && it.message == detail.message }
                                    ?.count ?: 0L
                            "<td>${if (count == 0L) "" else count.toString()}</td>"
                        }
                    (if (step == null) "<td class=\"text\">${escape(name)}</td>" else "") +
                        "<td class=\"text fail\">${escape(detail.code)}</td><td class=\"text msg\">${escape(detail.message)}</td>" +
                        "<td>${detail.count}</td>$perPhase"
                }
            }
        val header =
            (if (step == null) "<th>step</th>" else "") + "<th>code</th><th>message</th><th>calls</th>" +
                result.phases.joinToString("") { "<th>${escape(it)}</th>" }
        val failedLatency =
            all.failureLatency.takeIf { it.count > 0 }?.let {
                "<p class=\"muted\">Failed calls took p50 ${it.p50.formatMillis()} ms, max ${it.max.formatMillis()} ms.</p>"
            } ?: ""
        return "<h3 class=\"fail\">Failures (${all.ko} calls)</h3><table class=\"failures\"><tr>$header</tr>" +
            rows.joinToString("") { "<tr>$it</tr>" } + "</table>$failedLatency"
    }

    /** Whole runs (scenario executions): how many, how many failed, how long they took start to end. */
    private fun runsTable(result: LoadTestResult): String {
        val rows =
            (result.phases.map { it as String? } + null).joinToString("\n") { phase ->
                val d = result.runDuration(phase)
                "<tr><th>${escape(phase ?: "all phases")}</th><td>${result.runs(phase)}</td><td>${result.failedRuns(phase)}</td>" +
                    "<td>${d.p50.formatMillis()}</td><td>${d.p95.formatMillis()}</td><td>${d.p99.formatMillis()}</td>" +
                    "<td>${d.max.formatMillis()}</td></tr>"
            }
        return "<h3>Whole runs (scenario start to end)</h3><table><tr><th>phase</th><th>runs</th><th>failed</th>" +
            "<th>p50 ms</th><th>p95 ms</th><th>p99 ms</th><th>max ms</th></tr>$rows</table>"
    }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}

public class ReportFiles internal constructor(
    public val html: Path,
    public val json: Path,
)

/** Writes `report.html` and `summary.json` into a fresh, timestamped directory under [directory]. */
@JvmOverloads
public fun LoadTestResult.writeReports(
    directory: Path = Path.of("build", "reports", "loadtest"),
    title: String = scenario,
    verdict: Verdict? = null,
): ReportFiles {
    val runDirectory = directory.resolve(startedAt.toString().replace(":", "-"))
    Files.createDirectories(runDirectory)
    val html = Files.writeString(runDirectory.resolve("report.html"), HtmlReport.render(this, title, verdict))
    val json = Files.writeString(runDirectory.resolve("summary.json"), JsonSummary.render(this, verdict).toString())
    return ReportFiles(html, json)
}

/** Thrown by [finish] when the assertions did not pass; its message lists the failed ones. */
public class LoadTestFailedException(
    public val verdict: Verdict,
) : RuntimeException(
        "Load test assertions failed:\n" + verdict.results.filterNot { it.passed }.joinToString("\n") { "  $it" },
    )

/**
 * The usual end of a load test `main`: prints the console report, writes `report.html` and
 * `summary.json`, prints where they are, and throws [LoadTestFailedException] if [verdict] did not
 * pass - so the process (and `./gradlew runLoadTest`) exits non-zero without the caller touching
 * `System.exit`. Pass no verdict for runs without assertions (e.g. stress mode).
 */
@JvmOverloads
public fun LoadTestResult.finish(
    title: String = scenario,
    verdict: Verdict? = null,
    directory: Path = Path.of("build", "reports", "loadtest"),
): ReportFiles {
    ConsoleReport.print(this, verdict)
    val files = writeReports(directory, title, verdict)
    println("HTML report:  ${files.html.toAbsolutePath().toUri()}")
    println("JSON summary: ${files.json.toAbsolutePath()}")
    if (verdict != null && !verdict.passed) throw LoadTestFailedException(verdict)
    return files
}
