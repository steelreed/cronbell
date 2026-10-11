package com.steelreed.cronbell.server.notify;

/** No channel with this id in the caller's project. */
public class ChannelNotFoundException extends RuntimeException {

	public ChannelNotFoundException(String id) {
		super("no channel with id " + id);
	}

}
