FROM openjdk:14-jdk-buster

COPY idrovora.jar /app/idrovora.jar

VOLUME "/pipelines"
VOLUME "/jobs"

ENV IDROVORA_XPL_DIR /pipelines
ENV IDROVORA_JOB_DIR /jobs
ENV IDROVORA_HTTP_PORT 3000

WORKDIR "/"

ENTRYPOINT ["java", "-jar", "/app/idrovora.jar"]
