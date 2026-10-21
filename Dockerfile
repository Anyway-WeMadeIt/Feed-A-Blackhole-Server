# 서버 실행 이미지. 빌드 단계에서 jar를 만들고, 실행 단계에는 JRE와 jar만 담는다(소스·Gradle 캐시·.env는 이미지에 들어가지 않는다).
# 설정(DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET)은 이미지에 넣지 않고 실행할 때 환경변수로 주입한다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 단계를 먼저 분리해, 소스만 바뀐 빌드에서는 Gradle 다운로드 레이어를 재사용한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sh ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트와 Checkstyle은 CI에서 따로 돌리므로 이미지 빌드에서는 jar만 만든다.
RUN sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home --shell /usr/sbin/nologin blackhole
COPY --from=build /workspace/build/libs/app.jar app.jar
USER blackhole

EXPOSE 8080
# 컨테이너 메모리의 70%를 힙으로 쓰고, 메모리 부족이면 멈춰서 재시작 정책이 다시 띄우게 한다.
# GC는 Serial로 고정한다. 기본값은 메모리가 약 1.8GiB 이상이면 G1인데(운영은 컨테이너 제한 없이 호스트 2GiB를 본다), 이 규모의 힙에서는
# G1이 같은 부하에서 CPU를 20% 넘게 더 썼다(부하 테스트, loadtest/README.md). 부하 테스트는 컨테이너 1.5GiB 제한이라 기본이 Serial이었으므로,
# 이렇게 두면 운영도 시험한 구성과 같아진다.
# exec 형식이라 java가 PID 1로 종료 신호(SIGTERM)를 직접 받아 graceful shutdown이 동작한다.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
