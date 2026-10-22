package io.quarkiverse.goblin.internal;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.GoblinWebClient;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.WebClientInternal;

/**
 * Arms the Vert.x {@code WebClient}s the application creates, without any Goblin call in the application code. In dev
 * and test builds, Goblin rewrites every {@code WebClient.create(...)} and {@code WebClient.wrap(...)} call of the
 * application classes -- the core client as well as its Mutiny flavour -- to pass the new client to {@link #arm(Object)}.
 * A production build never carries Goblin, so its clients are never touched.
 */
public final class WebClientArming {

    private static final Logger LOG = Logger.getLogger(WebClientArming.class);

    private WebClientArming() {
    }

    /**
     * Attaches the Goblin interceptor to a client the application just created. A core client is armed directly; a
     * Mutiny client through the core client it delegates to. Anything else -- a custom decorator that does not delegate
     * to Vert.x's implementation -- is left untouched: arming must never break the application's own client.
     *
     * @param client the client returned by {@code WebClient.create(...)} or {@code WebClient.wrap(...)}
     */
    public static void arm(Object client) {
        try {
            Object core = client instanceof WebClient ? client : delegateOf(client);
            if (core instanceof WebClientInternal webClient) {
                GoblinWebClient.enable(webClient);
            }
        } catch (RuntimeException | ReflectiveOperationException e) {
            LOG.debugf("Goblin: could not arm the WebClient %s: %s", client, e.getMessage());
        }
    }

    private static Object delegateOf(Object client) throws ReflectiveOperationException {
        return client == null ? null : client.getClass().getMethod("getDelegate").invoke(client);
    }
}
