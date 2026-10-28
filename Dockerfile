# The jar is the same on every platform, so build it natively and only assemble the
# runtime image for the target platform (linux/arm64 on AWS).
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
# The app uses only free Vaadin components, so the production build needs no license key.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:25-jre
# A fixed uid, so the host's data volume can be owned by it (see infra/templates).
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home /app app \
    && mkdir -p /data && chown app:app /data
COPY --from=build /build/target/*.jar /app/app.jar
USER app
# app.db lives here; mount a volume so it survives new containers.
VOLUME /data
ENV PORT=8080 APP_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=70", \
    "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
