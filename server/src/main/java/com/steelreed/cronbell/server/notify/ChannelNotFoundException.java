package com.steelreed.cronbell.server.notify;

import com.steelreed.cronbell.server.shared.web.NotFoundException;

/** No channel with this id in the caller's project. */
public class ChannelNotFoundException extends NotFoundException {

	public ChannelNotFoundException(String id) {
		super("no channel with id " + id);
	}

}
