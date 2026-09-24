package io.quarkiverse.goblin;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The single implementation of the {@code quarkus.goblin.target} package and annotation rules, shared by the build-time
 * weaving of the service / messaging bindings (Jandex names) and by the inbound REST filter at runtime (reflection
 * names), so both sides can never diverge.
 */
public final class TargetRules {

    private final List<String> includePackages;
    private final List<String> excludePackages;
    private final Set<String> excludeAnnotations;

    /**
     * @param includePackages package prefixes to include, empty for all
     * @param excludePackages package prefixes to exclude
     * @param excludeAnnotations fully qualified names of the excluding annotations
     */
    public TargetRules(Collection<String> includePackages, Collection<String> excludePackages,
            Collection<String> excludeAnnotations) {
        this.includePackages = clean(includePackages);
        this.excludePackages = clean(excludePackages);
        this.excludeAnnotations = Set.copyOf(clean(excludeAnnotations));
    }

    /**
     * @param config the targeting configuration
     * @return the rules of the given configuration
     */
    public static TargetRules of(GoblinTargetingConfig config) {
        return new TargetRules(config.includePackages().orElse(List.of()), config.excludePackages().orElse(List.of()),
                config.excludeAnnotations().orElse(List.of()));
    }

    /**
     * Decides whether a package is targeted: excluded prefixes always win, then, when include prefixes are configured,
     * only matching packages are targeted.
     *
     * @param packageName the package of the candidate class
     * @return {@code true} when classes of the package may be assaulted
     */
    public boolean isPackageTargeted(String packageName) {
        if (matchesAnyPrefix(packageName, excludePackages)) {
            return false;
        }
        return includePackages.isEmpty() || matchesAnyPrefix(packageName, includePackages);
    }

    /**
     * @param annotationNames the fully qualified names of the annotations carried by a method or a class
     * @return {@code true} when one of them is an excluding annotation
     */
    public boolean isExcludedBy(Collection<String> annotationNames) {
        if (excludeAnnotations.isEmpty()) {
            return false;
        }
        for (String name : annotationNames) {
            if (excludeAnnotations.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyPrefix(String packageName, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> clean(Collection<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
