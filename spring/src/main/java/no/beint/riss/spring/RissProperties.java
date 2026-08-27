package no.beint.riss.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riss")
public class RissProperties {
    private boolean uiEnabled = true;
    private final Compatibility compatibility = new Compatibility();

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    public void setUiEnabled(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    public Compatibility getCompatibility() {
        return compatibility;
    }

    public static class Compatibility {
        private boolean enabled;
        private String primaryDocument;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrimaryDocument() {
            return primaryDocument;
        }

        public void setPrimaryDocument(String primaryDocument) {
            this.primaryDocument = primaryDocument;
        }
    }
}
