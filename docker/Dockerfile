# Built from artifacts produced on the host (./gradlew :server:installDist :web:assembleSite),
# so the image build needs neither Gradle nor Node.
FROM eclipse-temurin:25-jre

RUN useradd --system --uid 10001 app
WORKDIR /app
COPY server/build/install/server /app
COPY web/build/site /app/web

ENV WEB_ROOT=/app/web \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true"
USER app
EXPOSE 8080
# bash, not sh: /dev/tcp is a bash feature, and the JRE image ships no curl.
HEALTHCHECK --interval=5s --timeout=2s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/app/bin/server"]
