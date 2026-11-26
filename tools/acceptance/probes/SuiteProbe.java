import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Run the test classes for one resolved target/version. Exit nonzero even on accidental empty discovery. */
public final class SuiteProbe {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("com.p2s")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        var output = new PrintWriter(System.out, true);
        var summary = listener.getSummary();
        summary.printTo(output);
        summary.printFailuresTo(output);
        if (summary.getTestsFoundCount() == 0 || summary.getTestsSucceededCount() == 0
                || summary.getTotalFailureCount() != 0) {
            System.exit(1);
        }
    }
}
