FROM openjdk:8u151-jdk

ARG DOCKER_VERSION=17.09.0-ce
ARG DOCKER_SHA=a9e90a73c3cdfbf238f148e1ec0eaff5eb181f92f35bdd938fd7dab18e1c4647
ARG MAVEN_VERSION=3.5.2

RUN apt-get update && apt-get -y install coreutils git openssh-client curl zip unzip bash fonts-dejavu-core ca-certificates openssl groff python-pip python jq
RUN pip install awscli && apt-get -y purge python-pip && apt -y autoremove

# Install Docker (client)
RUN curl -fsSL "https://download.docker.com/linux/static/stable/$(uname -m)/docker-$DOCKER_VERSION.tgz" -o docker.tgz \
	&& echo "${DOCKER_SHA} *docker.tgz" | sha256sum -c - \
	&& tar -xzvf docker.tgz \
	&& mv docker/* /usr/local/bin/ \
	&& rmdir docker \
	&& rm docker.tgz \
	&& docker -v

# Install Maven
RUN curl -fsSL http://mirrors.ibiblio.org/apache/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz -o maven.tgz \
    && tar -xzvf maven.tgz \
    && mv apache-maven-$MAVEN_VERSION /usr/local \
    && rm maven.tgz

ENV PATH=/usr/local/apache-maven-$MAVEN_VERSION/bin:$PATH
