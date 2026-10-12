package com.steelreed.cronbell.server.checks;

import com.steelreed.cronbell.server.shared.web.NotFoundException;

/** No check with this slug in the caller's project. */
public class CheckNotFoundException extends NotFoundException {

	public CheckNotFoundException(String slug) {
		super("no check with slug '" + slug + "'");
	}

}
