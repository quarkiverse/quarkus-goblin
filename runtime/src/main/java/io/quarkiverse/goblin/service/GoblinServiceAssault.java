package io.quarkiverse.goblin.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.InterceptorBinding;

/**
 * Interceptor binding driving the service-layer chaos assaults. The Goblin deployment adds the binding onto application
 * bean classes at build time (respecting the {@code goblin.target} include/exclude rules), so application code never
 * references this annotation directly.
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface GoblinServiceAssault {

    /**
     * Supports the programmatic construction of {@code @GoblinServiceAssault} instances.
     */
    final class Literal extends AnnotationLiteral<GoblinServiceAssault> implements GoblinServiceAssault {

        public static final Literal INSTANCE = new Literal();

        private static final long serialVersionUID = 1L;
    }
}