package no.beint.riss.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(RissProperties.class)
public class RissAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    RissController rissController(RissProperties properties) {
        return new RissController(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "riss.compatibility", name = "enabled", havingValue = "true")
    RissCompatibilityController rissCompatibilityController(
            RissProperties properties,
            ObjectProvider<RissController> controller
    ) {
        var shared = controller.getIfUnique();
        return shared == null
                ? new RissCompatibilityController(properties)
                : new RissCompatibilityController(shared, properties);
    }
}
