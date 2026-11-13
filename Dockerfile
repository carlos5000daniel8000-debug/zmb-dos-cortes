FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/zmb-dos-cortes-1.0.0.jar app.jar
COPY index.html styles.css app.js ./
COPY assets ./assets
ENV DATA_DIR=/tmp/zmb-agenda-data
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
