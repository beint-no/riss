package no.beint.riss.spring;

import no.beint.riss.validation.RissValidationException;
import no.beint.riss.validation.RissValidator;
import no.beint.riss.validation.ValidationViolation;
import org.springframework.context.MessageSource;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Validates controller method parameters using Riss compile-time validators without AOP proxies.
 */
public final class RissRequestMappingHandlerAdapter extends RequestMappingHandlerAdapter {
    private final RissValidator validator;
    private final MessageSource messageSource;

    public RissRequestMappingHandlerAdapter(RissValidator validator, MessageSource messageSource) {
        this.validator = validator;
        this.messageSource = messageSource;
    }

    @Override
    protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
        return new RissInvocableHandlerMethod(handlerMethod, validator, messageSource);
    }

    private static final class RissInvocableHandlerMethod extends ServletInvocableHandlerMethod {
        private final RissValidator validator;
        private final MessageSource messageSource;

        private RissInvocableHandlerMethod(
                HandlerMethod handlerMethod,
                RissValidator validator,
                MessageSource messageSource
        ) {
            super(handlerMethod);
            this.validator = validator;
            this.messageSource = messageSource;
        }

        @Override
        protected Object[] getMethodArgumentValues(
                NativeWebRequest request,
                ModelAndViewContainer mavContainer,
                Object... providedArgs
        ) throws Exception {
            var args = super.getMethodArgumentValues(request, mavContainer, providedArgs);
            var violations = validator.validateMethodArguments(getBridgedMethod(), args);
            if (!violations.isEmpty()) {
                var resolved = messageSource == null
                        ? violations
                        : violations.stream()
                                .map(v -> new ValidationViolation(
                                        v.field(),
                                        RissSpringValidator.resolveMessage(v.message(), messageSource)
                                ))
                                .toList();
                throw new RissValidationException(resolved);
            }
            return args;
        }
    }
}
