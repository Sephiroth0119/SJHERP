package com.sjherp.app.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * API 边界密码强度（委托 {@link com.sjherp.domain.identity.PasswordPolicy}）。
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "密码至少 8 位，且须同时包含大写字母、小写字母和数字";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
