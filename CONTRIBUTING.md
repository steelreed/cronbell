# Contributing to Cronbell

Thanks for your interest. Cronbell is young and its core changes quickly, so please open an issue
before working on anything larger than a small fix.

## Build and test

Requirements: JDK 25, Node 24 or newer, Docker (for the PostgreSQL integration tests).

```bash
make test       # everything CI runs
make format     # format Java (Spring Java Format) and web code (Biome)
```

Or per part:

```bash
cd server && ./mvnw verify                  # also checks formatting
cd web && npm ci && npm run lint && npm run typecheck && npm test
```

## Pull requests

- Work on a branch and open a pull request against `main`.
- Behaviour changes start in [DESIGN.md](DESIGN.md): update the spec, then the code and its tests.
- One change per pull request, with tests that fail without it.
- Add a line to [CHANGELOG.md](CHANGELOG.md) when users will notice the change.
- CI must pass before review.

## Sign your commits (DCO)

Every commit needs a `Signed-off-by` line, which certifies the
[Developer Certificate of Origin](https://developercertificate.org/): you wrote the change or have
the right to submit it under the project's license.

```bash
git commit -s -m "fix(server): ..."
```

## Using AI tools

You are welcome to use AI assistants. You remain the author:

- Read, understand and test every line you submit. You must be able to explain the change in review.
- Do not open bulk or automated pull requests that no human has reviewed. They will be closed.
- Never paste secrets, credentials or private data into an AI tool.

## License

By contributing, you agree that your contribution to `server/` and `web/` is licensed under the
AGPL-3.0, and to client libraries and the starter under the Apache-2.0.

## Security and conduct

Report vulnerabilities through the
[security policy](https://github.com/steelreed/.github/blob/main/SECURITY.md), never a public issue.
Everyone taking part follows the
[code of conduct](https://github.com/steelreed/.github/blob/main/CODE_OF_CONDUCT.md).
