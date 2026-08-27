package no.beint.riss.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

final class RissRequestPath {
    private RissRequestPath() {
    }

    static String resolve(HttpServletRequest request, String endpointPath) {
        var servletMapping = request.getHttpServletMapping();
        var servletPath = MappingMatch.PATH.equals(servletMapping.getMappingMatch())
                && !"/*".equals(servletMapping.getPattern())
                ? request.getServletPath()
                : "";
        var rawPath = request.getContextPath() + servletPath + endpointPath;
        var firstNonSlash = 0;
        while (firstNonSlash < rawPath.length() && rawPath.charAt(firstNonSlash) == '/') {
            firstNonSlash++;
        }
        return UriUtils.encodePath("/" + rawPath.substring(firstNonSlash), StandardCharsets.UTF_8);
    }
}
