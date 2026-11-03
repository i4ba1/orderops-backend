FROM eclipse-temurin:21-jre
ARG SERVICE
ARG VERSION=0.1.0-SNAPSHOT
WORKDIR /app
COPY services/${SERVICE}/target/${SERVICE}-${VERSION}.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
