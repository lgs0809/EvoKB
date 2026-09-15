import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Cached real JUnit engines against frozen candidate classes; no models or business mutation. */
public final class GroundingFocusedJunitLauncher {
  public static void main(String[] args) throws Exception {
    var listener = new SummaryGeneratingListener();
    var builder = LauncherDiscoveryRequestBuilder.request();
    for (int i = 1; i < args.length; i++) {
      if (args[i].contains("#")) builder.selectors(DiscoverySelectors.selectMethod(args[i]));
      else builder.selectors(DiscoverySelectors.selectClass(args[i]));
    }
    LauncherFactory.create().execute(builder.build(), listener);
    var summary = listener.getSummary();
    var report = new LinkedHashMap<String, Object>();
    report.put("status", summary.getTestsFailedCount() == 0 && summary.getTestsSucceededCount() == summary.getTestsFoundCount()
        && summary.getTestsFoundCount() > 0 ? "PASS_FROZEN_GROUNDING_COMPONENT" : "FAIL_FROZEN_GROUNDING_COMPONENT");
    report.put("found", summary.getTestsFoundCount()); report.put("passed", summary.getTestsSucceededCount());
    report.put("failed", summary.getTestsFailedCount()); report.put("skipped", summary.getTestsSkippedCount());
    report.put("failures", summary.getFailures().stream().map(f -> Map.of("test", f.getTestIdentifier().getDisplayName(), "error", f.getException().toString())).toList());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
  }
}
