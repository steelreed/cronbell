package com.steelreed.cronbell.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the dashboard. The web app is built into {@code static/} of the image, and its
 * client-side routes (for example {@code /checks/backup}) all load {@code index.html}.
 * Paths with a dot are files and are left to the static resource handler, as is
 * everything under {@code /assets}, where Vite puts the bundles.
 */
@Controller
class WebAppController {

	@GetMapping({ "/", "/{path:^(?!api$|ping$|actuator$|assets$)[^.]*}",
			"/{path:^(?!api$|ping$|actuator$|assets$)[^.]*}/**" })
	String index() {
		return "forward:/index.html";
	}

}
