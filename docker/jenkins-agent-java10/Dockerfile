FROM docker:18.03.1-ce as docker
FROM maven:3.5.4 as maven
FROM openjdk:10.0.1-10-jdk

RUN apt-get update && apt-get -y install coreutils git openssh-client curl zip unzip bash fonts-dejavu-core ca-certificates openssl groff python-pip python jq
RUN pip install awscli && apt-get -y purge python-pip && apt -y autoremove

COPY --from=docker /usr/local/bin/docker /usr/bin/docker
COPY --from=maven /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
