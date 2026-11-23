# syntax=docker/dockerfile:1

# ---- build stage: full JDK + Maven wrapper ---------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY src/ src/
# Tests run separately (they need Docker for Testcontainers); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q package -DskipTests \
 && cp target/ledger-service-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime stage: JRE only, non-root -------------------------------------------------
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
# Dependencies change rarely, so they go in lower layers for faster rebuilds and pulls.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
