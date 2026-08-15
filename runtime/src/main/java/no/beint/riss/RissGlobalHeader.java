package no.beint.riss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(RissGlobalHeaders.class)
public @interface RissGlobalHeader {
    String name();

    String description() default "";

    String type() default "string";

    String format() default "";

    boolean required() default false;

    Class<?>[] skipTypes() default {};

    Class<?>[] onlyTypes() default {};

    String[] skipPackages() default {};
}
