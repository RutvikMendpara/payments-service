# Build from the repo root: docker build -f trade-simulator/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common common
COPY ledger-service/pom.xml ledger-service/
COPY settlement-service/pom.xml settlement-service/
COPY trade-simulator/pom.xml trade-simulator/
COPY trade-simulator/src trade-simulator/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -pl trade-simulator -am -DskipTests package \
    && java -Djarmode=tools -jar trade-simulator/target/trade-simulator-*.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
