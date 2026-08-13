package com.example.accountservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// @Retention(RUNTIME) is the part that matters: the annotation has to survive compilation for
// KycEnforcementAspect to still see it on the method at runtime.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresKyc {
}
