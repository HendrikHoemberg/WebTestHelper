package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;

@Configuration
public class ThymeleafLinkBuilderConfig {

    @Bean
    public static BeanPostProcessor thymeleafLinkBuilderPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof TemplateEngine templateEngine) {
                    templateEngine.setLinkBuilder(new LenientStandardLinkBuilder());
                }
                return bean;
            }
        };
    }
}
