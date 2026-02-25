# syntax=docker/dockerfile:1
FROM clojure:temurin-21-tools-deps AS base

RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cache dependency download as a separate layer
COPY deps.edn ./
RUN clojure -P

COPY . .

EXPOSE 8080

CMD ["clojure", "-M:run"]
