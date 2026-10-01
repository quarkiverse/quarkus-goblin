package io.quarkiverse.goblin.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import io.quarkiverse.goblin.GoblinTargetingConfig;
import io.quarkiverse.goblin.TargetRules;
import io.quarkiverse.goblin.dev.GoblinJsonRPCService;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

public class GoblinDevUIProcessor {

    /** The playbook shipped with the extension, exposed to AI agents as a Dev MCP resource. */
    static final String AGENT_PLAYBOOK = "goblin/agent-playbook.md";

    /*
     * Build-time data carrying a description is what Quarkus 3.38 serves as a Dev MCP resource
     * (quarkus://resource/build-time/quarkus-goblin_<name>): both resources only read, so both are enabled by default,
     * like the read-only tools. The names must not contain an underscore, which separates the namespace from the name.
     */
    static final String PLAYBOOK_RESOURCE = "agentPlaybook";
    static final String INVENTORY_RESOURCE = "resilienceInventory";

    static final String PLAYBOOK_DESCRIPTION = "Read this first, before any Goblin chaos experiment: the Goblin agent "
            + "playbook, in Markdown. Safety rules, what each chaos layer means for MicroProfile Fault Tolerance, how "
            + "to observe an experiment (including a circuit breaker's state), the hypothesis / assault / observation "
            + "/ conclusion loop, and the shape of the report to write.";

    static final String INVENTORY_DESCRIPTION = "Where this application believes it is protected: every method guarded "
            + "by a MicroProfile Fault Tolerance annotation (@Timeout, @Retry, @CircuitBreaker, @Fallback, @Bulkhead, "
            + "@RateLimit), indexed at build time from the application's own classes. Each entry gives the class, the "
            + "method, the 'method' tag of the ft.* metrics, the annotations with their effective parameters as "
            + "declared in the code (MicroProfile Config overrides are not reflected), and 'reachableBy', the Goblin "
            + "layers known to reach the method inside its Fault Tolerance interceptor (DATABASE is never listed: it also "
            + "reaches any guarded method that acquires a JDBC connection; SERVICE means the method is woven, but only "
            + "the first bean a request enters is assaulted). Derive the experiments "
            + "from it, then check each conclusion against the protection the method declares.";

    @BuildStep(onlyIfNot = IsProduction.class)
    JsonRPCProvidersBuildItem registerJsonRPCService() {
        return new JsonRPCProvidersBuildItem(GoblinJsonRPCService.class);
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    void createCardPages(BuildProducer<CardPageBuildItem> cardsProducer, ApplicationArchivesBuildItem applicationArchives,
            CombinedIndexBuildItem combinedIndex, GoblinTargetingConfig targeting) {
        CardPageBuildItem cardPage = new CardPageBuildItem();

        cardPage.setLogo("goblin-dark.svg", "goblin-light.svg");

        cardPage.addPage(Page.webComponentPageBuilder()
                .title("Chaos Dashboard")
                .icon("font-awesome-solid:bolt")
                .componentLink("qwc-goblin-dashboard.js"));

        cardPage.addPage(Page.webComponentPageBuilder()
                .title("History")
                .icon("font-awesome-solid:scroll")
                .componentLink("qwc-goblin-history.js"));

        cardPage.addBuildTimeData(PLAYBOOK_RESOURCE, readPlaybook(), PLAYBOOK_DESCRIPTION, true);
        cardPage.addBuildTimeData(INVENTORY_RESOURCE,
                ResilienceInventory.of(applicationArchives.getRootArchive().getIndex(), combinedIndex.getIndex(),
                        TargetRules.of(targeting)),
                INVENTORY_DESCRIPTION, true);

        cardsProducer.produce(cardPage);
    }

    static String readPlaybook() {
        try (InputStream in = GoblinDevUIProcessor.class.getClassLoader().getResourceAsStream(AGENT_PLAYBOOK)) {
            if (in == null) {
                throw new IllegalStateException(AGENT_PLAYBOOK + " is missing from the Goblin deployment jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + AGENT_PLAYBOOK, e);
        }
    }
}
