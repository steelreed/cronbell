# syntax=docker/dockerfile:1
# One image: the web dashboard is built first and served by the Spring Boot server.

FROM node:26-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS server
WORKDIR /server
COPY server/.mvn/ .mvn/
COPY server/mvnw server/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY server/src/ src/
COPY --from=web /web/dist/ src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && cp target/cronbell-server-*.jar /cronbell.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /data cronbell \
 && mkdir -p /data && chown cronbell /data
COPY --from=server /cronbell.jar /opt/cronbell/cronbell.jar
USER cronbell
ENV CRONBELL_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/cronbell/cronbell.jar"]
