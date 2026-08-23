#!/bin/sh
set -eu

WRAPPER_VERSION="3.9.11"
DIST_NAME="apache-maven-${WRAPPER_VERSION}"
WRAPPER_BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MAVEN_CACHE_DIR="${MAVEN_WRAPPER_CACHE:-${HOME}/.m2/wrapper/dists}"
MAVEN_DIST_DIR="${MAVEN_CACHE_DIR}/${DIST_NAME}-bin"
MAVEN_HOME="${MAVEN_DIST_DIR}/${DIST_NAME}"
MAVEN_ZIP="${MAVEN_DIST_DIR}/${DIST_NAME}-bin.zip"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${WRAPPER_VERSION}/${DIST_NAME}-bin.zip"

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  mkdir -p "${MAVEN_DIST_DIR}"
  if [ ! -f "${MAVEN_ZIP}" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "${MAVEN_URL}" -o "${MAVEN_ZIP}"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "${MAVEN_URL}" -O "${MAVEN_ZIP}"
    else
      echo "curl or wget is required to download Maven ${WRAPPER_VERSION}" >&2
      exit 1
    fi
  fi
  (
    cd "${MAVEN_DIST_DIR}"
    jar xf "${MAVEN_ZIP}"
  )
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
