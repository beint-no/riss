package no.beint.riss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A named string component schema. Enum values can be listed, or read from an enum type
 * at compile time ({@link #enumFrom()} + {@link #enumProperty()}).
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(RissStringSchemas.class)
public @interface RissStringSchema {
    String name();

    String description() default "";

    String pattern() default "";

    int minLength() default -1;

    int maxLength() default -1;

    String example() default "";

    String[] enumValues() default {};

    Class<?> enumFrom() default Void.class;

    String enumProperty() default "";

    String enumWhereProperty() default "";

    String enumWhereValue() default "";
}
