FROM bellsoft/liberica-openjre-alpine:17
VOLUME /tmp
RUN adduser -S jmix-user
USER jmix-user
COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
