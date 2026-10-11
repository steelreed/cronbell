# Changelog

All notable changes to Cronbell are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Ping API compatible with Healthchecks.io: `/ping/<key>`, `/start`, `/fail`, `/log`, exit codes.
- Cron schedules with time zones (5 or 6 fields, DST aware) and fixed-period schedules.
- Detector for missed runs, failed runs and runs that exceed their maximum runtime.
- Management API `/api/v1/checks` with project API keys; errors as RFC 9457 problem details.
- SQLite by default, PostgreSQL optional, one Flyway schema for both.
- OpenAPI description at `/api/v1/openapi.json`.
- The server serves the web dashboard.
- Container image built from one Dockerfile.
