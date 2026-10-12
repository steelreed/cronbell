package com.steelreed.cronbell.server.checks;

/** Why a check is DOWN. See DESIGN.md §4. */
public enum DownReason {

	MISSED, FAILED, RUNNING_TOO_LONG

}
