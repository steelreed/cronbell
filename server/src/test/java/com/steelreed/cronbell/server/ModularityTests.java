package com.steelreed.cronbell.server;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.CanvasOptions;
import org.springframework.modulith.docs.Documenter.DiagramOptions;

/**
 * Verifies the module structure (no cycles, only declared dependencies, no access to
 * another module's internals) and writes its documentation to
 * {@code target/spring-modulith-docs}: C4 component diagrams of the system and of each
 * module, and a canvas per module listing its beans, internal ones included.
 * ARCHITECTURE.md explains how to refresh the copies in {@code docs/architecture}.
 */
class ModularityTests {

	static final ApplicationModules modules = ApplicationModules.of(CronbellApplication.class);

	@Test
	void modulesRespectTheirBoundaries() {
		modules.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(modules).writeDocumentation(DiagramOptions.defaults(),
				CanvasOptions.defaults().revealInternals());
	}

}
