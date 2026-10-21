#!/usr/bin/env bash
# Publishes ghcr.io/lex00/floci:<short-sha> and :latest from the pushed develop head.
# Builds the jar once on the host, then packages it for each platform with
# docker/Dockerfile.jvm-package, so no platform runs Maven under emulation.
#
#   tools/ghcr-publish.sh               # linux/amd64,linux/arm64
#   PLATFORMS=linux/arm64 tools/ghcr-publish.sh
#   SKIP_LATEST=1 tools/ghcr-publish.sh # only the :<short-sha> tag
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

IMAGE="${IMAGE:-ghcr.io/lex00/floci}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER="${BUILDER:-floci-builder}"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "Working tree has uncommitted changes; commit or stash them first." >&2
    exit 1
fi
git fetch -q origin develop
head="$(git rev-parse HEAD)"
if [[ "$head" != "$(git rev-parse origin/develop)" ]]; then
    echo "HEAD $head is not origin/develop; push develop and check it out first." >&2
    exit 1
fi
sha="$(git rev-parse --short=7 HEAD)"

if ! docker buildx inspect "$BUILDER" >/dev/null 2>&1; then
    docker buildx create --name "$BUILDER" --driver docker-container >/dev/null
fi
gh auth token | docker login ghcr.io -u "$(gh api user -q .login)" --password-stdin >/dev/null

./mvnw -q -o clean package -DskipTests || ./mvnw -q clean package -DskipTests

tags=(-t "$IMAGE:$sha")
if [[ -z "${SKIP_LATEST:-}" ]]; then
    tags+=(-t "$IMAGE:latest")
fi

docker buildx build --builder "$BUILDER" --platform "$PLATFORMS" \
    -f docker/Dockerfile.jvm-package --build-arg VERSION="$sha" \
    "${tags[@]}" --push .

docker buildx imagetools inspect "$IMAGE:$sha" | sed -n 1,3p
echo "Published $IMAGE:$sha${SKIP_LATEST:+ (latest unchanged)}"
