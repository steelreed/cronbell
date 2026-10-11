# Common tasks. CI runs `make test`.
.PHONY: test test-server test-web format dev dev-server dev-web docker

test: test-server test-web

test-server:
	cd server && ./mvnw -B verify

test-web:
	cd web && npm ci --no-audit --no-fund && npm run lint && npm run typecheck && npm test

format:
	cd server && ./mvnw -B -q io.spring.javaformat:spring-javaformat-maven-plugin:apply
	cd web && npm run format

dev:
	$(MAKE) -j2 dev-server dev-web

dev-server:
	cd server && ./mvnw spring-boot:run

dev-web:
	cd web && npm run dev

# The image bundles the dashboard into the server (see Dockerfile).
docker:
	docker build -t ghcr.io/steelreed/cronbell:dev .
