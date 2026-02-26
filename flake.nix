{
  description = "Pseudovision -- IPTV scheduling engine";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    nix-helpers = {
      url = "github:fudoniten/nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, nix-helpers }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        helpers = nix-helpers.packages.${system};

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

      in {
        packages = {
          inherit pseudovision migratusRunner;
          default = pseudovision;

          deployContainer = helpers.deployContainers {
            name = "pseudovision";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let pseudovision = self.packages."${system}".pseudovision;
              in [ "${pseudovision}/bin/pseudovision" ];
          };

          migrationContainer = helpers.deployContainers {
            name = "pseudovision";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/migratus-runner" ];
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
          migrationContainer = {
            type = "app";
            program = let
              migrationContainer = self.packages."${system}".migrationContainer;
            in "${migrationContainer}/bin/deployContainers";
          };
        };

        devShells = {
          default = pkgs.mkShell {
            packages = with pkgs; [ clojure jdk21 ffmpeg postgresql ];
          };

          # nix develop .#deps -- update deps-lock.json
          deps = pkgs.mkShell {
            packages = with pkgs; [ clojure ];
            shellHook = ''
              echo "Run: clojure -Sforce -Sprepare"
            '';
          };
        };

        checks.tests = pkgs.runCommand "pseudovision-tests" {
          nativeBuildInputs = with pkgs; [ clojure jdk21 ];
          src = ./.;
        } ''
          cd $src
          clojure -M:test
          touch $out
        '';
      });
}
