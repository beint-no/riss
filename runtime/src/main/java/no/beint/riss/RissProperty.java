package no.beint.riss;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({})
public @interface RissProperty {
    String name();

    String type() default "string";

    String format() default "";

    boolean nullable() default false;

    String example() default "";

    String description() default "";

    String ref() default "";
}
