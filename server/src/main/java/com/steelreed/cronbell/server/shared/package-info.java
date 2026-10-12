/**
 * Shared: support every module uses and that depends on no module. Exposes two named
 * interfaces, {@code database} and {@code web}; the datasource set-up in
 * {@code shared.internal} is used by nobody directly.
 */
@ApplicationModule(displayName = "Shared", allowedDependencies = {})
package com.steelreed.cronbell.server.shared;

import org.springframework.modulith.ApplicationModule;
