FROM clojure:tools-deps

ARG BUILD_DATE
ARG VCS_REF
ARG VERSION

LABEL org.label-schema.build-date=$BUILD_DATE \
  org.label-schema.name="idrovora" \
  org.label-schema.description="A pump station for your XProc pipelines" \
  org.label-schema.url="https://github.com/gremid/idrovora" \
  org.label-schema.vcs-ref=$VCS_REF \
  org.label-schema.vcs-url="https://github.com/gremid/idrovora" \
  org.label-schema.vendor="Gregor Middell" \
  org.label-schema.version=$VERSION \
  org.label-schema.schema-version="1.0"

COPY . /app
WORKDIR /app

# Pull dependencies
RUN clojure -Stree

ENTRYPOINT ["/usr/local/bin/clojure", "-m", "idrovora.cli"]
