# ---------- Étape 1 : compilation avec Maven ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- Étape 2 : image d'exécution légère ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
RUN useradd --system --uid 1001 appuser
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
