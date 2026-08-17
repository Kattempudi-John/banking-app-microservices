package com.example.accountservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method that may only run for a user whose KYC verification is {@code APPROVED}.
 *
 * <p>Enforcement lives entirely in {@code KycEnforcementAspect}, which matches on this annotation
 * and runs before the annotated method. Because that interception happens on the Spring proxy, the
 * gate only fires for calls that arrive through the proxy: a call from one method of the same bean
 * to another, or an entry point that never touches an annotated service method at all, is not
 * gated. The Kafka-driven starter-account provisioning in {@code UserRegisteredListener} builds its
 * account directly for exactly that reason, so a brand-new user is provisioned while still sitting
 * at {@code PENDING_VERIFICATION}.
 *
 * <p>The aspect resolves the user from the JWT in the security context, never from the annotated
 * method's arguments, so annotating a method reachable without an authenticated caller makes it
 * fail rather than pass.
 *
 * <p>Retained at runtime because the aspect reads it reflectively; weakening the retention silently
 * disables every gate in the service.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresKyc {
}
