/**
 * Database support: which database runs (SQLite or PostgreSQL) and the SQL that differs,
 * row identifiers, and leases for jobs that one instance runs at a time.
 */
@NamedInterface("database")
package com.steelreed.cronbell.server.shared.database;

import org.springframework.modulith.NamedInterface;
