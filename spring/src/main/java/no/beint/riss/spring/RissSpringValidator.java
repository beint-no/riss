package no.beint.riss.spring;

import no.beint.riss.validation.RissValidator;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.SmartValidator;

/**
 * Spring {@link SmartValidator} backed by Riss compile-time validators.
 */
public final class RissSpringValidator implements SmartValidator {
    private final RissValidator validator;
    private final MessageSource messageSource;

    public RissSpringValidator(RissValidator validator, MessageSource messageSource) {
        this.validator = validator;
        this.messageSource = messageSource;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return true;
    }

    @Override
    public void validate(Object target, Errors errors) {
        validate(target, errors, new Object[0]);
    }

    @Override
    public void validate(Object target, Errors errors, Object... validationHints) {
        if (target == null) {
            return;
        }
        validator.validate(target, "", (field, rawMessage) -> {
            var message = resolveMessage(rawMessage, messageSource);
            if (field == null || field.isEmpty()) {
                errors.reject("riss.validation", message);
            } else if (errors instanceof BindingResult bindingResult) {
                bindingResult.addError(new FieldError(
                        bindingResult.getObjectName(),
                        field,
                        null,
                        false,
                        new String[]{"riss.validation"},
                        null,
                        message
                ));
            } else {
                errors.rejectValue(field, "riss.validation", message);
            }
        });
    }

    static String resolveMessage(String rawMessage, MessageSource messageSource) {
        if (messageSource == null || rawMessage == null || rawMessage.length() < 3) {
            return rawMessage;
        }
        if (rawMessage.charAt(0) == '{' && rawMessage.charAt(rawMessage.length() - 1) == '}') {
            var key = rawMessage.substring(1, rawMessage.length() - 1);
            return messageSource.getMessage(key, null, rawMessage, LocaleContextHolder.getLocale());
        }
        return rawMessage;
    }
}
