package com.steelreed.cronbell.server.check;

public class CheckNotFoundException extends RuntimeException {

	public CheckNotFoundException(String slug) {
		super("no check with slug '" + slug + "'");
	}

}
