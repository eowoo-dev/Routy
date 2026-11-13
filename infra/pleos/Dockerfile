FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_AVD_HOME=/data/avd \
    ANDROID_USER_HOME=/data/android \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip python3 openjdk-21-jdk-headless \
    libgl1 libegl1 libpulse0 libnss3 libx11-6 libxcb1 libxcomposite1 \
    libxcursor1 libxi6 libxrandr2 libxtst6 libxkbcommon0 libasound2t64 \
    libvulkan1 libglu1-mesa \
    && rm -rf /var/lib/apt/lists/*

# The legacy manager supports unattended SDK installation without an IDE.
RUN curl -fL --retry 3 -o /tmp/tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && echo 'd313adb7aedccf6cf0cfca51ec180f0059f5f8f8  /tmp/tools.zip' | sha1sum -c - \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && unzip -q /tmp/tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/tools.zip \
    && yes | sdkmanager --licenses >/dev/null
RUN sdkmanager 'platform-tools' 'emulator' 'platforms;android-34' 'build-tools;34.0.0'

RUN apt-get update && apt-get install -y --no-install-recommends libpng16-16t64 libxkbfile1 \
    && rm -rf /var/lib/apt/lists/*

ENV HOME=/data/home
COPY entrypoint.py /opt/pleos/entrypoint.py
ENTRYPOINT ["python3", "-u", "/opt/pleos/entrypoint.py"]
