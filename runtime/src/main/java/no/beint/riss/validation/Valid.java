package no.beint.riss.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller argument, property, or container element for compile-time Riss validation.
 * Its simple name ({@code Valid}) also triggers Spring MVC's built-in validation hook without Jakarta Validation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
public @interface Valid {
}
