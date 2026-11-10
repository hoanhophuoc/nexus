# JavaFX on Linux uses Pango for text layout. The stock Gradle image ships
# neither libpangoft2 nor a display, so add the runtime library for the
# headless test container.
ARG GRADLE_IMAGE=gradle:9.8.2-jdk27
FROM ${GRADLE_IMAGE}

USER root
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libpangoft2-1.0-0 \
    && rm -rf /var/lib/apt/lists/*
USER gradle