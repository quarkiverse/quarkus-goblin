package io.quarkiverse.goblin.internal.messaging;

import java.lang.annotation.*;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.InterceptorBinding;

/**
 * Interceptor binding driving the messaging-layer chaos assaults. The Goblin deployment adds the binding onto the
 * {@code @Incoming} consumer methods of the application at build time (respecting the {@code goblin.target} rules),
 * and only when Quarkus Messaging is present, so application code never references this annotation directly.
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface GoblinMessagingAssault {

    /**
     * Supports the programmatic construction of {@code @GoblinMessagingAssault} instances.
     */
    final class Literal extends AnnotationLiteral<GoblinMessagingAssault> implements GoblinMessagingAssault {

        public static final Literal INSTANCE = new Literal();

        private static final long serialVersionUID = 1L;
    }
}
