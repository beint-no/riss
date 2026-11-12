package no.beint.riss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(RissDefaultResponses.class)
public @interface RissDefaultResponse {
    String code();

    String description();

    String contentType() default "";

    String schemaRef() default "";

    /** Document the response only on operations whose path has a variable, such as a 404 for an unknown ID. */
    boolean pathVariablesOnly() default false;
}
