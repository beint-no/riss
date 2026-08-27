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
        return encodePreservingPercentTriplets("/" + rawPath.substring(firstNonSlash));
    }

    private static String encodePreservingPercentTriplets(String path) {
        var encoded = new StringBuilder(path.length());
        var unencodedStart = 0;
        for (var index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '%'
                    && index + 2 < path.length()
                    && isHexDigit(path.charAt(index + 1))
                    && isHexDigit(path.charAt(index + 2))) {
                encoded.append(UriUtils.encodePath(path.substring(unencodedStart, index), StandardCharsets.UTF_8));
                encoded.append(path, index, index + 3);
                index += 2;
                unencodedStart = index + 1;
            }
        }
        return encoded.append(UriUtils.encodePath(path.substring(unencodedStart), StandardCharsets.UTF_8)).toString();
    }

    private static boolean isHexDigit(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }
}
