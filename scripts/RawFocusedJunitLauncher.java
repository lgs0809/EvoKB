import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Actual cached JUnit Platform/Jupiter engines; no Maven or models. */
public final class RawFocusedJunitLauncher {
  public static void main(String[] args) throws Exception {
    var listener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request().selectors(
        DiscoverySelectors.selectClass("cn.lgs.knowledgebase.application.raw.RawKnowledgeBuilderTest"),
        DiscoverySelectors.selectClass("cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapterTest"),
        DiscoverySelectors.selectClass("cn.lgs.knowledgebase.infrastructure.parser.RoutingParserAdapterTest")).build();
    LauncherFactory.create().execute(request, listener);
    var summary = listener.getSummary();
    var report = new LinkedHashMap<String, Object>();
    report.put("status", summary.getTestsFailedCount() == 0 && summary.getTestsSucceededCount() == summary.getTestsFoundCount()
        && summary.getTestsFoundCount() > 0 ? "PASS_NATIVE_FOCUSED_JUNIT" : "FAIL_NATIVE_FOCUSED_JUNIT");
    report.put("found", summary.getTestsFoundCount()); report.put("passed", summary.getTestsSucceededCount());
    report.put("failed", summary.getTestsFailedCount()); report.put("skipped", summary.getTestsSkippedCount());
    report.put("failures", summary.getFailures().stream().map(f -> Map.of("test", f.getTestIdentifier().getDisplayName(), "error", f.getException().toString())).toList());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
  }
}
