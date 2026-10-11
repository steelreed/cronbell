package com.steelreed.cronbell.server.check;

/** One message from a job. See DESIGN.md §5. */
public enum PingKind {

	START, SUCCESS, FAIL, LOG

}
