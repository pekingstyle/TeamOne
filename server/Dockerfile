# TeamOne Server（多阶段构建：宿主机无需 JDK/Maven 也可用）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY teamone-shared/pom.xml teamone-shared/
COPY teamone-platform/pom.xml teamone-platform/
COPY teamone-prd/pom.xml teamone-prd/
COPY teamone-collab/pom.xml teamone-collab/
COPY teamone-eng/pom.xml teamone-eng/
COPY teamone-insight/pom.xml teamone-insight/
COPY teamone-app/pom.xml teamone-app/
RUN mvn -q -B -DskipTests dependency:go-offline || true
COPY . .
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /
COPY --from=build /app/teamone-app/target/teamone-app.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app.jar"]
