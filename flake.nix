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

      in {
        packages = {
          inherit pseudovision migratusRunner;
          default = pseudovision;

          deployContainer = helpers.deployContainers {
            name = "pseudovision";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            environmentPackages = [ pkgs.ffmpeg ];
            environment = {
              GIT_COMMIT = self.rev or self.dirtyRev or "unknown";
              BUILD_TIMESTAMP = builtins.toString builtins.currentTime;
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
        } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux integrationTests);
      });
}
