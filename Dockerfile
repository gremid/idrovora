FROM clojure:tools-deps
COPY . /app
WORKDIR /app
# Pull dependencies
RUN clojure -Stree
ENTRYPOINT ["/usr/local/bin/clojure", "-m", "idrovora.cli"]
