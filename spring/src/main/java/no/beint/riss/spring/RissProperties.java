package no.beint.riss.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riss")
public class RissProperties {
    private boolean uiEnabled = true;
    private String primaryDocument;

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    public void setUiEnabled(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    public String getPrimaryDocument() {
        return primaryDocument;
    }

    public void setPrimaryDocument(String primaryDocument) {
        this.primaryDocument = primaryDocument;
    }
}
