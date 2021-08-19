package link.thingscloud.spring.boot.common.aop.annotation;

import link.thingscloud.spring.boot.common.aop.enums.LoggingLevel;

import java.lang.annotation.*;

@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Logging {
    LoggingLevel level() default LoggingLevel.INFO;

    boolean result() default true;
}
