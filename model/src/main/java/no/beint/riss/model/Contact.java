package no.beint.riss.model;

public record Contact(String name, String email, String url) {
    public static Contact of(String name, String email, String url) {
        if (isBlank(name) && isBlank(email) && isBlank(url)) {
            return null;
        }
        return new Contact(emptyToNull(name), emptyToNull(email), emptyToNull(url));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
