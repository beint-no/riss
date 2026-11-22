package no.beint.riss.validation;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Static validation helpers invoked by Riss-generated compile-time validators.
 */
public final class ValidationSupport {
    private ValidationSupport() {
    }

    public static String path(String prefix, String segment) {
        return prefix.isEmpty() ? segment : prefix + "." + segment;
    }

    public static String indexedPath(String prefix, int index) {
        return prefix + "[" + index + "]";
    }

    public static String mapPath(String prefix, Object key) {
        return prefix + "[" + key + "]";
    }

    public static boolean isNotBlank(CharSequence value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNotEmpty(Object value) {
        return value != null && lengthOf(value) > 0;
    }

    public static int lengthOf(Object value) {
        if (value instanceof CharSequence cs) {
            return cs.length();
        }
        if (value instanceof Collection<?> c) {
            return c.size();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        if (value instanceof Object[] arr) {
            return arr.length;
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        throw new IllegalArgumentException("Unsupported size/length target: " + (value == null ? "null" : value.getClass().getName()));
    }

    public static boolean isValidOptionalEmail(CharSequence value) {
        return value == null || value.isEmpty() || isValidEmail(value);
    }

    public static boolean isValidEmail(CharSequence value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int len = value.length();
        int atIndex = -1;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == '@') {
                if (atIndex >= 0) {
                    return false;
                }
                atIndex = i;
            } else if (c <= 0x20 || c == 0x7F) {
                return false;
            }
        }
        if (atIndex <= 0 || atIndex >= len - 1) {
            return false;
        }
        int localLen = atIndex;
        int domainLen = len - atIndex - 1;
        if (localLen > 64 || domainLen > 255) {
            return false;
        }
        if (value.charAt(0) == '.' || value.charAt(atIndex - 1) == '.') {
            return false;
        }
        boolean prevDot = false;
        for (int i = 0; i < atIndex; i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (prevDot) {
                    return false;
                }
                prevDot = true;
            } else {
                prevDot = false;
                if (!isLocalEmailChar(c)) {
                    return false;
                }
            }
        }
        return isValidDomain(value, atIndex + 1, len);
    }

    private static boolean isLocalEmailChar(char c) {
        if (c > 0x7F) {
            return !Character.isWhitespace(c) && !Character.isISOControl(c);
        }
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || "!#$%&'*+/=?^_`{|}~-".indexOf(c) >= 0;
    }

    private static boolean isValidDomain(CharSequence value, int start, int end) {
        if (value.charAt(start) == '.' || value.charAt(end - 1) == '.') {
            return false;
        }
        int labelStart = start;
        for (int i = start; i <= end; i++) {
            if (i == end || value.charAt(i) == '.') {
                int labelLen = i - labelStart;
                if (labelLen == 0 || labelLen > 63) {
                    return false;
                }
                char first = value.charAt(labelStart);
                char last = value.charAt(i - 1);
                if (first == '-' || last == '-') {
                    return false;
                }
                for (int j = labelStart; j < i; j++) {
                    char c = value.charAt(j);
                    boolean ok = (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-'
                            || (c > 0x7F && Character.isLetterOrDigit(c));
                    if (!ok) {
                        return false;
                    }
                }
                labelStart = i + 1;
            }
        }
        return true;
    }

    public static boolean minLong(Object value, long min) {
        if (value == null) {
            return true;
        }
        if (value instanceof Integer i) return i >= min;
        if (value instanceof Long l) return l >= min;
        if (value instanceof Short s) return s >= min;
        if (value instanceof Byte b) return b >= min;
        var bd = toBigDecimal(value);
        return bd != null && bd.compareTo(BigDecimal.valueOf(min)) >= 0;
    }

    public static boolean maxLong(Object value, long max) {
        if (value == null) {
            return true;
        }
        if (value instanceof Integer i) return i <= max;
        if (value instanceof Long l) return l <= max;
        if (value instanceof Short s) return s <= max;
        if (value instanceof Byte b) return b <= max;
        var bd = toBigDecimal(value);
        return bd != null && bd.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    public static boolean decimalMin(Object value, BigDecimal bound, boolean inclusive) {
        if (value == null) {
            return true;
        }
        var bd = toBigDecimal(value);
        if (bd == null) {
            return false;
        }
        int cmp = bd.compareTo(bound);
        return inclusive ? cmp >= 0 : cmp > 0;
    }

    public static boolean decimalMax(Object value, BigDecimal bound, boolean inclusive) {
        if (value == null) {
            return true;
        }
        var bd = toBigDecimal(value);
        if (bd == null) {
            return false;
        }
        int cmp = bd.compareTo(bound);
        return inclusive ? cmp <= 0 : cmp < 0;
    }

    public static boolean isPositive(Object value) {
        return signum(value) > 0;
    }

    public static boolean isPositiveOrZero(Object value) {
        int s = signum(value);
        return s >= 0 && s != Integer.MIN_VALUE;
    }

    public static boolean isNegative(Object value) {
        int s = signum(value);
        return s < 0 && s != Integer.MIN_VALUE;
    }

    public static boolean isNegativeOrZero(Object value) {
        int s = signum(value);
        return s <= 0 && s != Integer.MIN_VALUE;
    }

    private static int signum(Object value) {
        if (value instanceof Integer i) return Integer.compare(i, 0);
        if (value instanceof Long l) return Long.compare(l, 0L);
        if (value instanceof Short s) return Short.compare(s, (short) 0);
        if (value instanceof Byte b) return Byte.compare(b, (byte) 0);
        if (value instanceof BigDecimal bd) return bd.signum();
        if (value instanceof BigInteger bi) return bi.signum();
        if (value instanceof Double d) return Double.isNaN(d) ? Integer.MIN_VALUE : Double.compare(d, 0.0);
        if (value instanceof Float f) return Float.isNaN(f) ? Integer.MIN_VALUE : Float.compare(f, 0.0f);
        var bd = toBigDecimal(value);
        return bd == null ? Integer.MIN_VALUE : bd.signum();
    }

    public static boolean checkDigits(Object value, int maxInteger, int maxFraction) {
        if (value == null) {
            return true;
        }
        var bd = toBigDecimal(value);
        if (bd == null) {
            return false;
        }
        int integerPartLength = bd.precision() - bd.scale();
        int fractionPartLength = Math.max(bd.scale(), 0);
        return maxInteger >= integerPartLength && maxFraction >= fractionPartLength;
    }

    public static boolean isPast(Object value) {
        return compareTemporal(value) < 0;
    }

    public static boolean isPastOrPresent(Object value) {
        return compareTemporal(value) <= 0;
    }

    public static boolean isFuture(Object value) {
        return compareTemporal(value) > 0;
    }

    public static boolean isFutureOrPresent(Object value) {
        return compareTemporal(value) >= 0;
    }

    private static int compareTemporal(Object value) {
        if (value instanceof LocalDate d) return d.compareTo(LocalDate.now());
        if (value instanceof LocalDateTime dt) return dt.compareTo(LocalDateTime.now());
        if (value instanceof Instant i) return i.compareTo(Instant.now());
        if (value instanceof OffsetDateTime odt) return odt.compareTo(OffsetDateTime.now(odt.getOffset()));
        if (value instanceof ZonedDateTime zdt) return zdt.compareTo(ZonedDateTime.now(zdt.getZone()));
        if (value instanceof YearMonth ym) return ym.compareTo(YearMonth.now());
        if (value instanceof Year y) return y.compareTo(Year.now());
        if (value instanceof LocalTime t) return t.compareTo(LocalTime.now());
        if (value instanceof OffsetTime ot) return ot.compareTo(OffsetTime.now(ot.getOffset()));
        if (value instanceof Date d) return d.toInstant().compareTo(Instant.now());
        throw new IllegalArgumentException("Unsupported temporal target: " + value.getClass().getName());
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof BigInteger bi) return new BigDecimal(bi);
        if (value instanceof Integer i) return BigDecimal.valueOf(i);
        if (value instanceof Long l) return BigDecimal.valueOf(l);
        if (value instanceof Short s) return BigDecimal.valueOf(s);
        if (value instanceof Byte b) return BigDecimal.valueOf(b);
        if (value instanceof Double d) return Double.isFinite(d) ? BigDecimal.valueOf(d) : null;
        if (value instanceof Float f) return Float.isFinite(f) ? BigDecimal.valueOf(f.doubleValue()) : null;
        if (value instanceof CharSequence cs) {
            try {
                return new BigDecimal(cs.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
