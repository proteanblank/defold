FROM ubuntu:18.04

# Base stuff
RUN \
    apt-get update && \
    apt-get install -y software-properties-common && \
    apt-get install --no-install-recommends gcc-5 && \
    apt-get install --no-install-recommends g++-5 && \
    apt-get install --no-install-recommends libssl-dev && \
    apt-get install --no-install-recommends openssl && \
    apt-get install --no-install-recommends libtool && \
    apt-get install --no-install-recommends autoconf && \
    apt-get install --no-install-recommends automake && \
    apt-get install --no-install-recommends build-essential && \
    apt-get install --no-install-recommends uuid-dev && \
    apt-get install --no-install-recommends libxi-dev && \
    apt-get install --no-install-recommends libopenal-dev && \
    apt-get install --no-install-recommends libgl1-mesa-dev && \
    apt-get install --no-install-recommends libglw1-mesa-dev && \
    apt-get install --no-install-recommends freeglut3-dev && \
    apt-get install --no-install-recommends tofrodos && \
    apt-get install --no-install-recommends tree && \
    apt-get install --no-install-recommends silversearcher-ag && \
    apt-get install --no-install-recommends valgrind && \
    apt-get install --no-install-recommends lib32z1

ENV workdir .

WORKDIR ${workdir}

ENTRYPOINT ${workdir}/ci/ci.sh
