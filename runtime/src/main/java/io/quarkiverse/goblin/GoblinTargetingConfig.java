package io.quarkiverse.goblin;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Targeting rules selecting which classes chaos applies to. Fixed at build time: the service and messaging layers weave
 * their interceptor bindings from these rules, so changing them requires a rebuild (a dev-mode live reload does it).
 * The inbound REST filter applies the very same rules at runtime, through {@link TargetRules}.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.goblin.target")
public interface GoblinTargetingConfig {

    /**
     * Package prefixes to include (empty means all packages).
     */
    Optional<List<String>> includePackages();

    /**
     * Package prefixes to exclude. An exclusion always wins over an inclusion.
     */
    Optional<List<String>> excludePackages();

    /**
     * Annotations to exclude: methods carrying one of these annotations, or declared in a class carrying one, are never
     * assaulted (HTTP_IN, SERVICE and MESSAGING layers alike).
     */
    Optional<List<String>> excludeAnnotations();
}
