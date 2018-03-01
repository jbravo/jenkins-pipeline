FROM docker:17.12.0-ce as docker
FROM maven:3.5.2 as maven
FROM openjdk:9.0.1-11-jdk

RUN apt-get update && apt-get -y install coreutils git openssh-client curl zip unzip bash fonts-dejavu-core ca-certificates openssl groff python-pip python jq
RUN pip install awscli && apt-get -y purge python-pip && apt -y autoremove

# The PKCS12 cacerts from the base image is not working for some reason. Using the JKS cacerts from the -slim image here.
# java.security properties keystore.type and keystore.type.compat are resp. set to 'pkcs12' and 'true', so it is strange.
COPY cacerts /etc/ssl/certs/java/cacerts

COPY --from=docker /usr/local/bin/docker /usr/bin/docker
COPY --from=maven /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
