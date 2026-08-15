package no.beint.riss.spring;

import no.beint.riss.SpecSet;
import no.beint.riss.SpecSets;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(RissProperties.class)
public class RissAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    SpecSet rissSpec(RissProperties properties) {
        return SpecSets.required(properties.getSpec());
    }

    @Bean
    @ConditionalOnMissingBean
    RissController rissController(SpecSet spec, RissProperties properties) {
        return new RissController(spec, properties);
    }
}
