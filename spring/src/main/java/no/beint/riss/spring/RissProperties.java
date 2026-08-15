package no.beint.riss.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riss")
public class RissProperties {
    private String path = "/openapi";
    private String uiPath = "/openapi/ui";
    private boolean uiEnabled = true;
    private String spec = "";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getUiPath() {
        return uiPath;
    }

    public void setUiPath(String uiPath) {
        this.uiPath = uiPath;
    }

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    public void setUiEnabled(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }
}
