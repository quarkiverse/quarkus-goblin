package io.quarkiverse.goblin.deployment;

import io.quarkiverse.goblin.dev.GoblinJsonRPCService;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

public class GoblinDevUIProcessor {

    @BuildStep
    JsonRPCProvidersBuildItem registerJsonRPCService() {
        return new JsonRPCProvidersBuildItem(GoblinJsonRPCService.class);
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    void createCardPages(BuildProducer<CardPageBuildItem> cardsProducer) {
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

        cardsProducer.produce(cardPage);
    }
}
