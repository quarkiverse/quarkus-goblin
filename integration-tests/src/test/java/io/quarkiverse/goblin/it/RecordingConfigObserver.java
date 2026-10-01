package io.quarkiverse.goblin.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.goblin.AssaultConfigChange;
import io.quarkiverse.goblin.AssaultObserver;

/**
 * A test-only {@link AssaultObserver} bean recording the configuration changes, the way an application or a test records
 * the attack it went through.
 */
@ApplicationScoped
public class RecordingConfigObserver implements AssaultObserver {

    private final List<AssaultConfigChange> changes = new CopyOnWriteArrayList<>();

    @Override
    public void onConfigChange(AssaultConfigChange change) {
        changes.add(change);
    }

    public List<AssaultConfigChange> changes() {
        return List.copyOf(changes);
    }

    public void clear() {
        changes.clear();
    }
}
