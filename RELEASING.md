# Releasing Cronbell

A release is a `v*` tag on `main`. Pushing it runs [`release.yml`](.github/workflows/release.yml),
which

1. checks the tag (`vX.Y.Z`, or `vX.Y.Z-alpha.N`, `-beta.N`, `-rc.N` for a pre-release), that the
   tagged commit is on `main`, and that `CHANGELOG.md` has a dated section for the version,
2. builds `ghcr.io/steelreed/cronbell` for `linux/amd64` and `linux/arm64` and pushes it with
   provenance, an SBOM and a GitHub build attestation,
3. creates the GitHub Release with that changelog section as its notes, marked as a pre-release
   for `-alpha`, `-beta` and `-rc` versions.

Nothing is published if step 1 fails. Only the maintainer creates tags.

Image tags per release:

| Tag pushed | Image tags |
|---|---|
| `v0.1.0-beta.1` | `0.1.0-beta.1`, `beta` |
| `v0.1.0` | `0.1.0`, `0.1`, `latest` |

## Before a release

- [ ] `main` is green in CI (server, web, container image, deploy kit).
- [ ] `make test` passes locally on the commit you will tag.
- [ ] Every user-visible change since the last release is in `CHANGELOG.md` under `[Unreleased]`.
- [ ] Database migrations in the release start cleanly on a copy of a real database (SQLite and,
      if anything changed there, PostgreSQL).
- [ ] `docs/self-hosting.md`, `deploy/.env.example` and `server/README.md` describe any new
      setting, and the example `CRONBELL_VERSION` points at the version you are releasing.
- [ ] Upgrading from the previous release was tried with the deploy kit (`docker compose pull`,
      `up -d`, dashboard and pings work).

## Cut a release

The example is `0.1.0-beta.1`; replace it with the version you release.

1. On a branch, update `CHANGELOG.md`: move the `[Unreleased]` entries into a new section and
   date it, leaving an empty `[Unreleased]` on top:

   ```markdown
   ## [Unreleased]

   ## [0.1.0-beta.1] - 2026-12-01
   ```

   The release workflow refuses a missing, empty or undated section (`- TBD` counts as undated).
   Check the notes locally: `.github/scripts/changelog-notes.sh 0.1.0-beta.1`.
2. Set `CRONBELL_VERSION` in `deploy/.env.example` and the version in the examples of
   `docs/self-hosting.md` and `README.md` to the new version if they changed.
3. Open a pull request, wait for CI, merge it.
4. Tag the merge commit on `main` and push the tag:

   ```bash
   git switch main && git pull
   git tag -a v0.1.0-beta.1 -m "Cronbell 0.1.0-beta.1"
   git push origin v0.1.0-beta.1
   ```

   The version number is not stored anywhere else: the image and release take it from the tag.
5. Watch the run under **Actions → Release**. A failed run can be re-run; the GitHub Release step
   updates an existing release instead of failing.
6. Verify:
   - The release page `https://github.com/steelreed/cronbell/releases/tag/v0.1.0-beta.1` has the
     notes, and is marked as a pre-release for a pre-release version.
   - The image has both platforms:
     `docker buildx imagetools inspect ghcr.io/steelreed/cronbell:0.1.0-beta.1`
   - The image pulls without logging in (after the first release, see below):
     `docker logout ghcr.io; docker pull ghcr.io/steelreed/cronbell:0.1.0-beta.1`
   - The attestation verifies:
     `gh attestation verify oci://ghcr.io/steelreed/cronbell:0.1.0-beta.1 --repo steelreed/cronbell`
7. Deploy (below), or upgrade your servers by hand as in `docs/self-hosting.md`.

If a tag points at the wrong commit and the image was already pushed, do not move the tag: fix
the problem and release the next version (`0.1.0-beta.2`).

## After the first release: make the image public

GitHub publishes a new container package as **private**, whatever the repository's visibility
("When you first publish a package, the default visibility is private", GitHub Docs,
[Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)).
Until it is public, `docker pull` needs a login and the deploy kit does not work for anyone else.
Once, after the first release run:

1. Open `https://github.com/orgs/steelreed/packages/container/package/cronbell`, then
   **Package settings**.
2. Under **Danger Zone**, **Change visibility** → **Public**. This cannot be undone: a public
   package cannot be made private again.
3. Check that the package is linked to `steelreed/cronbell` (publishing from the workflow with
   `GITHUB_TOKEN` links it) so the repository's README and releases show it.

If the organization does not allow public packages, an organization owner enables it first under
**Organization settings → Packages → Package creation** (enable public packages).

The GitHub build attestation is skipped while the repository is private: GitHub offers artifact
attestations for public repositories on the Free and Team plans.

## Deploy to the beta server (optional)

[`deploy.yml`](.github/workflows/deploy.yml) upgrades one server that runs the deploy kit: over
SSH it sets `CRONBELL_VERSION` in `~/cronbell/.env`, runs `docker compose pull` and
`docker compose up -d`, and polls the health endpoint for up to 150 seconds. Without its secrets
it skips with a notice, so it is safe to leave in place.

### Setup, once

1. On the server, use a dedicated user that can run Docker, with the deploy kit in
   `~/cronbell` (`docker-compose.yml`, `Caddyfile`, `.env`). Make Cronbell's health endpoint
   reachable from the server itself on `127.0.0.1:8095`, for example with
   `~/cronbell/docker-compose.override.yml`:

   ```yaml
   services:
     cronbell:
       ports:
         - "127.0.0.1:8095:8080"
   ```

   (Or point `DEPLOY_HEALTH_URL` at another URL that the server can reach.)
2. Create a key pair for the workflow only, and allow it on the server:

   ```bash
   ssh-keygen -t ed25519 -N '' -C cronbell-deploy -f cronbell-deploy
   # append cronbell-deploy.pub to ~/.ssh/authorized_keys of the deploy user
   ssh-keyscan -p 22 your.server.example > known_hosts   # check the fingerprints against the server
   ```

3. In the repository, **Settings → Environments → New environment** `beta`. Add yourself under
   **Required reviewers** to approve every deploy, and add these **environment secrets**:

   | Secret | Value |
   |---|---|
   | `DEPLOY_HOST` | Server host name or IP address |
   | `DEPLOY_PORT` | SSH port (optional, default 22) |
   | `DEPLOY_USER` | SSH user |
   | `DEPLOY_SSH_KEY` | The private key (`cronbell-deploy`), whole file |
   | `DEPLOY_KNOWN_HOSTS` | The `known_hosts` lines of the server |

   Then delete the local private key file.
4. Optional **variables** (Settings → Secrets and variables → Actions → Variables):

   | Variable | Default | Meaning |
   |---|---|---|
   | `DEPLOY_ON_RELEASE` | unset | `true` deploys every successful release automatically |
   | `DEPLOY_DIR` | `cronbell` | Deploy kit directory, relative to the user's home |
   | `DEPLOY_HEALTH_URL` | `http://127.0.0.1:8095/actuator/health` | Health URL as seen from the server |

### Deploy and roll back

- Deploy: **Actions → Deploy → Run workflow**, version `0.1.0-beta.1` (without the `v`), then
  approve it in the `beta` environment. With `DEPLOY_ON_RELEASE=true` this happens after each
  successful release.
- Roll back: run the same workflow with the previous version. By hand on the server:

  ```bash
  cd ~/cronbell
  sed -i "s/^CRONBELL_VERSION=.*/CRONBELL_VERSION=<previous>/" .env
  docker compose pull && docker compose up -d
  ```

  Migrations only go forward. If the newer version changed the database and the older one does
  not start, restore the backup taken before the upgrade (`docs/self-hosting.md`, "Restore a
  backup"). Take that backup (`./backup.sh`) before deploying a version with new migrations.
