{
  description = "Pseudovision -- IPTV scheduling engine";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    nix-helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, nix-helpers }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        helpers = nix-helpers.legacyPackages.${system};

        pseudovision = helpers.mkClojureBin {
          name = "pseudovision/pseudovision";
          primaryNamespace = "pseudovision.main";
          src = ./.;
        };

        migratusRunner = helpers.mkClojureBin {
          name = "pseudovision/migratus-runner";
          primaryNamespace = "pseudovision.db.migrate";
          src = ./.;
        };

        integrationTests =
          import ./integration-tests.nix { inherit pkgs pseudovision; };

        # `nix run .#integration-test` — brings up an ephemeral PostgreSQL,
        # runs migrations and the filler integration test against it, then
        # tears the database down.  Run from the repository root.
        integrationTestRunner = pkgs.writeShellApplication {
          name = "pseudovision-integration-test";
          runtimeInputs = with pkgs; [ postgresql_16 clojure jdk21 coreutils ];
          text = ''
            if [ ! -f deps.edn ]; then
              echo "error: run from the repository root (deps.edn not found)" >&2
              exit 1
            fi

            workdir="$(mktemp -d)"
            export PGDATA="$workdir/data"

            cleanup() {
              if [ -d "$PGDATA" ]; then
                pg_ctl -D "$PGDATA" -m immediate stop >/dev/null 2>&1 || true
              fi
              rm -rf "$workdir"
            }
            trap cleanup EXIT

            port="$(( (RANDOM % 2000) + 55000 ))"
            echo "Starting ephemeral PostgreSQL in $workdir (port $port)..."
            initdb -U postgres -A trust "$PGDATA" >/dev/null
            pg_ctl -D "$PGDATA" -w \
              -o "-p $port -k $workdir -c listen_addresses=localhost" \
              start >/dev/null
            createdb -h localhost -p "$port" -U postgres pseudovision

            export PSEUDOVISION_TEST_DB_URL="jdbc:postgresql://localhost:$port/pseudovision"
            export PSEUDOVISION_TEST_DB_USER="postgres"
            export PSEUDOVISION_TEST_DB_PASS=""

            echo "Running filler integration test..."
            clojure -M:test --focus pseudovision.scheduling.filler-integration-test
          '';
        };

      in {
        packages = {
          inherit pseudovision migratusRunner integrationTestRunner;
          default = pseudovision;

          deployContainer = let
            # Get git commit timestamp for versioning
            gitTimestamp = if self ? lastModified then
              toString self.lastModified
            else
              "unknown";
            # Create version tag from timestamp (YYYYMMDD-HHMMSS format)
            versionTag = if self ? lastModified then
              builtins.substring 0 8 gitTimestamp # Use YYYYMMDD
            else
              "dev";
          in helpers.deployContainers {
            name = "pseudovision";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" versionTag ];
            # NB: use pathEnv (not environmentPackages) so these land on the
            # container's $PATH, not just in the image filesystem.  The
            # helper computes PATH = makeBinPath (basePackages ++ pathEnv);
            # environmentPackages only populates contents.  Keeping ffmpeg on
            # PATH lets the app resolve a bare "ffmpeg" and survive a stale or
            # changed absolute FFMPEG_PATH (e.g. after a nixpkgs bump rehashes
            # ffmpeg) instead of failing with ENOENT.
            pathEnv = with pkgs; [ ffmpeg procps ];
            env = {
              GIT_COMMIT = self.rev or self.dirtyRev or "unknown";
              GIT_TIMESTAMP = gitTimestamp;
              VERSION_TAG = versionTag;
              FFMPEG_PATH = "${pkgs.ffmpeg}/bin/ffmpeg";
              FFPROBE_PATH = "${pkgs.ffmpeg}/bin/ffprobe";
            };
            entrypoint =
              let pseudovision = self.packages."${system}".pseudovision;
              in [ "${pseudovision}/bin/pseudovision" ];
          };

          deployMigrationContainer = helpers.deployContainers {
            name = "pseudovision-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/migratus-runner" ];
          };

          deployContainersScript = pkgs.writeShellApplication {
            name = "deployContainers";
            text = ''
              ${self.packages."${system}".deployContainer}/bin/deployContainers
              ${
                self.packages."${system}".deployMigrationContainer
              }/bin/deployContainers
            '';
          };
        };

        apps = {
          pseudovision = {
            type = "app";
            program = "${pseudovision}/bin/pseudovision";
          };
          migrate = {
            type = "app";
            program = "${migratusRunner}/bin/migratus-runner";
          };
          integration-test = {
            type = "app";
            program =
              "${integrationTestRunner}/bin/pseudovision-integration-test";
          };
          deployContainer = {
            type = "app";
            program =
              let deployContainer = self.packages."${system}".deployContainer;
              in "${deployContainer}/bin/deployContainers";
          };
          deployMigrationContainer = {
            type = "app";
            program = let
              deployMigrationContainer =
                self.packages."${system}".deployMigrationContainer;
            in "${deployMigrationContainer}/bin/deployContainers";
          };
          deployContainers = {
            type = "app";
            program = let
              deployContainersScript =
                self.packages."${system}".deployContainersScript;
            in "${deployContainersScript}/bin/deployContainers";
          };
        };

        devShells = rec {

          default = updateDeps;

          updateDeps = pkgs.mkShell {
            buildInputs =
              [ (helpers.updateClojureDeps { aliases = [ "test" ]; }) ];
          };

          pseudovision = pkgs.mkShell {
            packages = with pkgs; [ clojure jdk21 ffmpeg postgresql ];
          };
        };

        checks = {
          tests = helpers.mkClojureTests {
            name = "pseudovision/pseudovision";
            src = ./.;
          };

          lint = pkgs.runCommand "pseudovision-lint" {
            nativeBuildInputs = [ pkgs.clj-kondo ];
          } ''
            # clj-kondo exits non-zero on errors (--fail-level error);
            # warnings are printed but do not fail the check.
            clj-kondo \
              --lint ${./src} ${./test} \
              --fail-level error
            touch $out
          '';
        } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux integrationTests);
      });
}
