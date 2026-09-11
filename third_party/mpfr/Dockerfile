# Backup Linux build environment for MPFR+GMP.
# Primary on Windows: WSL2 or MSYS2 (MinGW-w64 / UCRT64 shell) — same
# configure/make sequence as `build.sh` below.
#
# Pin Ubuntu LTS (24.04) for stability.
# Upgrade procedure:
#   1. bump the `FROM` line
#   2. run `docker compose build`
#   3. run `docker compose run --rm mpfr-build bash -c 'make check'`
#   4. update `third_party/mpfr/README.md` if successful.

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential \
      autoconf \
      libgmp-dev \
      ca-certificates \
      xz-utils \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY fetch.sh build.sh ./

# The pinned upstream tarballs (and their sha256 sentinel markers) are checked
# into the repository. COPY them so fetch.sh inside the build reuses the
# pre-verified tarballs instead of downloading at image-build time — this keeps
# the build hermetic and removes curl from the toolchain dependencies.
COPY *.tar.xz *.tar.xz.sha256-verified ./

# Build at image-build time so the container is ready-to-use.
RUN bash fetch.sh && bash build.sh

# Default to an interactive shell for iterative work.
CMD ["bash"]
