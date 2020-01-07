FROM ubuntu:18.04

# Basic dependencies
RUN apt-get update
RUN apt-get install -y software-properties-common
RUN \
  apt-get install -y --no-install-recommends \
    gcc-5 \
    g++-5 \
    libssl-dev \
    openssl \
    libtool \
    autoconf \
    automake \
    build-essential \
    uuid-dev \
    libxi-dev \
    libopenal-dev \
    libgl1-mesa-dev \
    libglw1-mesa-dev \
    freeglut3-dev \
    tofrodos \
    tree \
    silversearcher-ag \
    valgrind \
    lib32z1

# Install python and easy_install
RUN \
  apt-get install -y python2.7 python-setuptools && \
  sh -c "echo \#\!/usr/bin/env bash > /usr/local/bin/easy_install" && \
  sh -c "echo python /usr/lib/python2.7/dist-packages/easy_install.py $\* >> /usr/local/bin/easy_install" && \
  chmod +x /usr/local/bin/easy_install

# ENTRYPOINT "./ci/ci.sh"
