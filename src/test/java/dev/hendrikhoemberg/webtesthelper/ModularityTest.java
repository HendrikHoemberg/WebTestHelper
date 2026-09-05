package dev.hendrikhoemberg.webtesthelper;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.modulith.core.ApplicationModules;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(WebtesthelperApplication.class);

    @Test
    void modulesRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }

    @Test
    void moduleStructureIsPrintable() {
        MODULES.forEach(module -> System.out.println(module.getDisplayName()));
    }

    @Test
    void repositoriesDoNotQueryEntitiesOutsideTheirOwnModule() {
        ClassPathScanningCandidateComponentProvider entityScanner =
                new ClassPathScanningCandidateComponentProvider(false);
        entityScanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Map<String, String> entityModules = new HashMap<>();
        for (BeanDefinition bd : entityScanner.findCandidateComponents("dev.hendrikhoemberg.webtesthelper")) {
            String className = bd.getBeanClassName();
            if (className != null) {
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                String module = moduleOf(className);
                entityModules.put(simpleName, module);
            }
        }

        ClassPathScanningCandidateComponentProvider repoScanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        repoScanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<String> violations = new ArrayList<>();
        for (BeanDefinition bd : repoScanner.findCandidateComponents("dev.hendrikhoemberg.webtesthelper")) {
            try {
                String repoClassName = bd.getBeanClassName();
                if (repoClassName == null) continue;
                Class<?> repoClass = Class.forName(repoClassName);
                String repoModule = moduleOf(repoClass.getName());

                for (Method method : repoClass.getDeclaredMethods()) {
                    Query query = method.getAnnotation(Query.class);
                    if (query != null && !query.value().isBlank()) {
                        String jpql = query.value();
                        for (Map.Entry<String, String> entry : entityModules.entrySet()) {
                            String entityName = entry.getKey();
                            String entityModule = entry.getValue();
                            if (jpql.matches(".*\\b" + entityName + "\\b.*")) {
                                if (!entityModule.equals(repoModule)) {
                                    violations.add(String.format(
                                            "%s.%s queries %s (module '%s') from module '%s' in query: %s",
                                            repoClass.getSimpleName(), method.getName(),
                                            entityName, entityModule, repoModule, jpql));
                                }
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        assertThat(violations)
                .as("Repositories must not query entities outside their own module in @Query strings")
                .isEmpty();
    }

    private static String moduleOf(String className) {
        String prefix = "dev.hendrikhoemberg.webtesthelper.";
        if (className.startsWith(prefix)) {
            String sub = className.substring(prefix.length());
            int dot = sub.indexOf('.');
            return dot > 0 ? sub.substring(0, dot) : sub;
        }
        return className;
    }
}
