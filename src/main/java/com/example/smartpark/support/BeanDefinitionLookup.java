package com.example.smartpark.support;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.ResolvableType;

/**
 * Internal helper shared by bean-definition registrars. Not part of the
 * application's public API; only Spring registration code should depend on it.
 */
public final class BeanDefinitionLookup {

    private BeanDefinitionLookup() {
    }

    public static boolean hasBean(BeanDefinitionRegistry registry, Class<?> type) {
        return beanNameFor(registry, type) != null;
    }

    public static String beanNameFor(BeanDefinitionRegistry registry, Class<?> type) {
        ConfigurableListableBeanFactory beanFactory = registry instanceof ConfigurableListableBeanFactory factory
                ? factory : null;
        for (String name : registry.getBeanDefinitionNames()) {
            Class<?> candidate = resolveType(registry, beanFactory, name);
            if (candidate != null && type.isAssignableFrom(candidate)) return name;
        }
        return null;
    }

    /**
     * Bean definitions declared by {@code @Bean} methods carry no resolvable type
     * before instantiation, so fall back to the bean factory's ability to predict
     * the factory-method return type.
     */
    public static Class<?> resolveType(BeanDefinitionRegistry registry,
                                       ConfigurableListableBeanFactory beanFactory, String name) {
        BeanDefinition definition = registry.getBeanDefinition(name);
        if (definition.getResolvableType() != ResolvableType.NONE) {
            return definition.getResolvableType().toClass();
        }
        return beanFactory == null ? null : beanFactory.getType(name, false);
    }
}
