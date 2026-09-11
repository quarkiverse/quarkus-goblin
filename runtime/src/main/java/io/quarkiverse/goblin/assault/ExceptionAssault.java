package io.quarkiverse.goblin.assault;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

@ApplicationScoped
public class ExceptionAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(ExceptionAssault.class);

    @Override
    public AssaultType type() {
        return AssaultType.EXCEPTION;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isExceptionEnabled();
    }

    @Override
    public String recordLabel() {
        return "exception";
    }

    @Override
    public AssaultOutcome apply(AssaultContext context) {
        MutableAssaultConfig config = context.getConfig();
        LOG.debugf("Goblin: injecting exception on %s", context.getMethodName());
        context.getEngine().recordAssault(context.getMethodName(), recordLabel());
        throw createException(config);
    }

    private RuntimeException createException(MutableAssaultConfig config) {
        String type = config.getExceptionType();
        String message = config.getExceptionMessage();
        try {
            Class<?> clazz = Class.forName(type);
            var ctor = clazz.getConstructor(String.class);
            return (RuntimeException) ctor.newInstance(message);
        } catch (Exception e) {
            return new RuntimeException(message);
        }
    }
}