package no.beint.riss.spring;

import no.beint.riss.validation.RissValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
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

    @Bean
    @ConditionalOnMissingBean
    RissValidator rissValidator() {
        return RissValidator.load();
    }

    @Bean
    @ConditionalOnMissingBean
    RissSpringValidator rissSpringValidator(RissValidator validator, ObjectProvider<MessageSource> messageSource) {
        return new RissSpringValidator(validator, messageSource.getIfAvailable());
    }

    @Bean
    WebMvcConfigurer rissValidationMvcConfigurer(RissSpringValidator validator) {
        return new WebMvcConfigurer() {
            @Override
            public Validator getValidator() {
                return validator;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    RissValidationExceptionHandler rissValidationExceptionHandler() {
        return new RissValidationExceptionHandler();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcRegistrations.class)
    static class RissWebMvcRegistrationsConfiguration {
        @Bean
        @ConditionalOnMissingBean(WebMvcRegistrations.class)
        WebMvcRegistrations rissWebMvcRegistrations(
                RissValidator validator,
                ObjectProvider<MessageSource> messageSource
        ) {
            return new WebMvcRegistrations() {
                @Override
                public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                    return new RissRequestMappingHandlerAdapter(validator, messageSource.getIfAvailable());
                }
            };
        }
    }
}
