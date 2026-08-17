package com.example.transactionservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that may only run for a caller whose identity verification is {@code APPROVED}.
 *
 * <p>{@link com.example.transactionservice.aspect.KycEnforcementAspect} matches this annotation at
 * runtime and advises the method before its body starts, so a refused call executes no part of the
 * target and — where the target is transactional — opens no transaction to roll back. Retention
 * must stay {@code RUNTIME}: dropping it removes the gate without any compile-time signal.
 *
 * <p>The gate resolves the user from the JWT, so it vets the <em>caller</em> and nothing else. A
 * method that credits an account belonging to somebody else must additionally call
 * {@link com.example.transactionservice.service.RecipientKycValidator}; this annotation alone
 * leaves the receiving side unchecked.
 *
 * <p>Effective only on calls that pass through the Spring proxy. A self-invocation from inside the
 * same bean bypasses the aspect entirely and the method runs ungated.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresKyc {
}
