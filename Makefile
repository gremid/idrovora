default: docker_build

.PHONY: test
test:
	@clojure -A:test -m cognitect.test-runner

docker_build: VERSION = $(shell git describe --tags --always)
docker_build:
	@docker build \
		--build-arg BUILD_DATE=`date -u +"%Y-%m-%dT%H:%M:%SZ"` \
		--build-arg VCS_REF=`git rev-parse --short HEAD` \
		--build-arg VERSION=$(VERSION) \
		-t gremid/idrovora:$(VERSION) .
