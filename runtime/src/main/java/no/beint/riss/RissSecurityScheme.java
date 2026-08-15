package no.beint.riss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(RissSecuritySchemes.class)
public @interface RissSecurityScheme {
    String name();

    String type() default "http";

    String scheme() default "bearer";

    String bearerFormat() default "";

    String headerName() default "";

    String description() default "";
}
