package io.quarkiverse.goblin.it.aesh;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A plain service bean called by a command: it stays eligible for service assaults, unlike the command itself.
 */
@ApplicationScoped
public class GreetingService {

    public String greet(String name) {
        return "hello " + name;
    }
}
