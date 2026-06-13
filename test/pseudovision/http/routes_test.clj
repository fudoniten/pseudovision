(ns pseudovision.http.routes-test
  "Compile-time guard for the HTTP route table.

   Reitit refuses to build a router with conflicting paths (e.g. a static
   segment shadowed by a sibling path parameter, like /slots/reorder vs
   /slots/:id). Without this test that failure only surfaces when the router
   is built during system startup — i.e. in production, as an Integrant
   build exception. Building the router here pulls the failure forward into
   `nix flake check`."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.http.core :as http]))

(def ^:private test-ctx
  ;; Route building never invokes the handlers, so nil/empty deps are fine.
  {:db nil :ffmpeg {} :media {} :scheduling {}})

(deftest router-builds-without-conflicts
  (testing "the full route table compiles into a reitit router"
    ;; make-handler calls (reitit.ring/router (routes ctx) ...), which throws
    ;; :path-conflicts on any overlapping paths. A clean build => no conflicts.
    (is (some? (http/make-handler test-ctx))
        "make-handler should build the router without throwing a route conflict")))
