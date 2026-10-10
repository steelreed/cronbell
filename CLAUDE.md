# CLAUDE.md — Steelreed Cron

Monitoring for scheduled and background jobs (working name). Read `DESIGN.md` first: it is the
specification, and behaviour changes start there.

## Layout

| Directory | What | Toolchain |
|---|---|---|
| `server/` | Spring Boot 4.1 service: ping endpoint, management API, detector, notifier | Java 25 (`.java-version` pins Temurin 25 via jenv), Maven wrapper |
| `web/` | Dashboard SPA, built into `web/dist` and served by the server | Node 26, Vite 8, React 19, TypeScript 7, TanStack Router/Query, Tailwind 4, shadcn/ui, Biome |
| `starter/` | (later) `cron-spring-boot-starter`, Java 17 baseline, Spring Boot 3 and 4 | Maven |

## Commands

```bash
cd server && ./mvnw verify          # tests, needs Docker for Testcontainers
cd web && npm run lint && npm run build && npm test
```

Both must pass before every commit.

## Conventions

- Maven groupId `com.steelreed`, packages `com.steelreed.cron.*`.
- Licence: AGPL-3.0 for `server/` and `web/`; Apache-2.0 for `starter/`.
- English in code, comments, docs and commits. Talk to the maintainer in Vietnamese.
- Never add AI attribution (Co-Authored-By, "Generated with") to commits or PRs.
- Every change goes through a branch and a PR once the GitHub repo exists. The maintainer merges.
- Never read, print or commit secrets (`.env`, API keys, SMTP/Telegram tokens).
- shadcn/ui components live in `web/src/components/ui` and are generated code: add them with
  `npx shadcn@latest add <name>`, don't hand-edit unless needed.
