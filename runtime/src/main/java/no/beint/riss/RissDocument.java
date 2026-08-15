package no.beint.riss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time document metadata. Place this on a dedicated type in the API module.
 * Scan packages, paths, and the document name are read from this type.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface RissDocument {
    String name() default "api";

    String title();

    String version() default "1";

    String description() default "";

    String contactName() default "";

    String contactEmail() default "";

    String contactUrl() default "";

    String[] scanPackages() default {};

    String[] paths() default {};

    String[] excludePaths() default {};

    String[] security() default {};
}
