FROM node:24-alpine AS web-build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-17 AS java-build
WORKDIR /src
COPY java ./java
RUN mvn -f java/pom.xml -DskipTests package \
    && cp java/ekb-bootstrap/target/evokb.jar /out.jar

FROM eclipse-temurin:22-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home ekb \
    && mkdir -p /app/web /var/lib/enterprise-kb/staging \
    && chown -R ekb:ekb /app /var/lib/enterprise-kb
COPY --from=java-build --chown=ekb:ekb /out.jar /app/app.jar
COPY --from=web-build --chown=ekb:ekb /src/web/dist/ /app/web/
USER ekb
EXPOSE 18080
ENV EKB_WEB_DIR=/app/web
ENTRYPOINT ["java","-jar","/app/app.jar"]
