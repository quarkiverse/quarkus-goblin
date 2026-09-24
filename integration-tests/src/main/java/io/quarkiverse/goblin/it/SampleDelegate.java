package io.quarkiverse.goblin.it;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * A second business bean called by {@link SampleService#nested()}, used to prove that a bean-to-bean call inside an
 * armed request is assaulted only once, at the outermost intercepted call.
 */
@ApplicationScoped
public class SampleDelegate {

    private static final Logger LOG = Logger.getLogger(SampleDelegate.class);

    public String inner() {
        LOG.debugf("SampleDelegate.inner() executing - nested call, never assaulted a second time");
        return "inner reply";
    }
}
