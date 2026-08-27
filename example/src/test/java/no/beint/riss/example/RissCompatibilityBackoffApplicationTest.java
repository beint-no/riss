package no.beint.riss.example;

import no.beint.riss.spring.RissCompatibilityController;
import no.beint.riss.spring.RissProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@SpringBootTest(properties = "riss.compatibility.enabled=true")
@Import(RissCompatibilityBackoffApplicationTest.CustomCompatibilityConfiguration.class)
class RissCompatibilityBackoffApplicationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void autoConfigurationBacksOffForApplicationController() {
        assertArrayEquals(
                new String[]{"customRissCompatibilityController"},
                context.getBeanNamesForType(RissCompatibilityController.class)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomCompatibilityConfiguration {
        @Bean
        RissCompatibilityController customRissCompatibilityController(RissProperties properties) {
            return new RissCompatibilityController(properties);
        }
    }
}
