FROM bellsoft/liberica-openjdk-debian:21 AS builder

WORKDIR /workspace

ARG SPRING_PROFILES_ACTIVE=prod

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle package.json ./
COPY frontend ./frontend
COPY src ./src

RUN chmod +x gradlew
RUN ./gradlew --no-daemon clean bootJar -x test -Pvaadin.productionMode=true -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}
RUN find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} build/app.jar \;

FROM bellsoft/liberica-openjre-debian:21

WORKDIR /app

ARG SPRING_PROFILES_ACTIVE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}

RUN groupadd --system finassist \
    && useradd --system --gid finassist --home-dir /app finassist \
    && mkdir -p /storage \
    && chown -R finassist:finassist /app /storage

USER finassist

VOLUME ["/storage"]

COPY --from=builder /workspace/build/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
