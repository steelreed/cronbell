package com.steelreed.cronbell.server.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller of the management API ({@code /api/v1}). Errors of these controllers
 * are answered as RFC 9457 problem details by the exception handlers of the modules,
 * which all apply to controllers carrying this annotation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ManagementApi {

}
