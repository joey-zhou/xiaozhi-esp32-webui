FROM eclipse-temurin:8u442-b06-jdk-jammy AS builder

WORKDIR /app
COPY . .
RUN sed -i "s@http://.*archive.ubuntu.com@http://mirrors.huaweicloud.com@g" /etc/apt/sources.list && \
    sed -i "s@http://.*security.ubuntu.com@http://mirrors.huaweicloud.com@g" /etc/apt/sources.list && \
    apt-get update && apt-get install -y curl unzip && \
    curl -sLO https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz && \
    tar -xzf apache-maven-3.9.9-bin.tar.gz && \
    rm apache-maven-3.9.9-bin.tar.gz && \
    mv apache-maven-3.9.9 /usr/local/apache-maven && \
    ln -s /usr/local/apache-maven/bin/mvn /usr/local/bin/mvn

ARG VOSK_MODEL=vosk-model-cn-0.22
RUN curl -O https://alphacephei.com/vosk/models/${VOSK_MODEL}.zip && \
    unzip ${VOSK_MODEL}.zip && \
    rm ${VOSK_MODEL}.zip && \
    mv ${VOSK_MODEL} models/vosk-model

RUN mvn clean package -DskipTests

FROM node:lts-bullseye AS web
WORKDIR /app/web
COPY web .
RUN npm install && npm run build

FROM eclipse-temurin:8u442-b06-jdk-jammy

RUN sed -i "s@http://.*archive.ubuntu.com@http://mirrors.huaweicloud.com@g" /etc/apt/sources.list && \
    sed -i "s@http://.*security.ubuntu.com@http://mirrors.huaweicloud.com@g" /etc/apt/sources.list && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y ffmpeg nodejs
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
COPY --from=builder /app/models /app/models
COPY --from=web /app/web /app/web
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x entrypoint.sh
VOLUME [ "/app/audio" ]
EXPOSE 8084 8091
ENV DB_HOST=127.0.0.1 DB_USER=xiaozhi DB_PASSWORD=123456 DB_NAME=xiaozhi
CMD [ "/app/entrypoint.sh" ]
