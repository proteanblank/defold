FROM ubuntu:18.04

RUN apt-get update
RUN apt-get install -y software-properties-common
RUN apt-get install -y --no-install-recommends gcc-5
RUN apt-get install -y --no-install-recommends g++-5
RUN apt-get install -y --no-install-recommends libssl-dev
RUN apt-get install -y --no-install-recommends openssl
RUN apt-get install -y --no-install-recommends libtool
RUN apt-get install -y --no-install-recommends autoconf
RUN apt-get install -y --no-install-recommends automake
RUN apt-get install -y --no-install-recommends build-essential
RUN apt-get install -y --no-install-recommends uuid-dev
RUN apt-get install -y --no-install-recommends libxi-dev
RUN apt-get install -y --no-install-recommends libopenal-dev
RUN apt-get install -y --no-install-recommends libgl1-mesa-dev
RUN apt-get install -y --no-install-recommends libglw1-mesa-dev
RUN apt-get install -y --no-install-recommends freeglut3-dev
RUN apt-get install -y --no-install-recommends tofrodos
RUN apt-get install -y --no-install-recommends tree
RUN apt-get install -y --no-install-recommends silversearcher-ag
RUN apt-get install -y --no-install-recommends valgrind
RUN apt-get install -y --no-install-recommends lib32z1

ENV workdir .

WORKDIR ${workdir}

ENTRYPOINT ${workdir}/ci/ci.sh
