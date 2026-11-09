(ns pseudovision.db.playouts-test
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [pseudovision.db.playouts :as sut]))

(deftest bulk-insert-splits-large-batches
  ;; One INSERT binds ~8 parameters per event and PostgreSQL allows 65535, so
  ;; a long build's events must go in several statements.
  (let [statements (atom [])]
    (with-redefs [jdbc/execute! (fn [_ sql-vec] (swap! statements conj (count (rest sql-vec))))]
      (sut/bulk-insert-events! nil (for [i (range 4500)]
                                     {:playout-id 1 :media-item-id i :is-manual false}))
      (is (= 3 (count @statements)))
      (is (every? #(< % 65535) @statements)))))
