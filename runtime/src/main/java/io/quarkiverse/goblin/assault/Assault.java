package io.quarkiverse.goblin.assault;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

public interface Assault {

    AssaultType type();

    boolean isEnabled(MutableAssaultConfig config);

    String recordLabel();

    int order();

    AssaultOutcome apply(AssaultContext context);
}