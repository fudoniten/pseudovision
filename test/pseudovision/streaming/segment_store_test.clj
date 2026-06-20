(ns pseudovision.streaming.segment-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [pseudovision.streaming.segment-store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "segstore-test" (make-array FileAttribute 0))))

(defn- temp-file [content]
  (let [f (java.io.File/createTempFile "seg-src" ".ts")]
    (spit f content)
    f))

(deftest put-then-read-and-delete
  (let [base (temp-dir)
        s    (store/local-disk-store base)
        uuid "chan-1"
        src  (temp-file "SEGMENT-DATA")]
    (testing "put moves the source file into the store"
      (store/put-segment! s uuid "seg-0.ts" src)
      (is (not (.exists src)) "source is moved, not copied")
      (is (store/segment-exists? s uuid "seg-0.ts")))
    (testing "open returns the stored bytes"
      (is (= "SEGMENT-DATA" (slurp (store/open-segment s uuid "seg-0.ts")))))
    (testing "missing segment opens as nil"
      (is (nil? (store/open-segment s uuid "nope.ts"))))
    (testing "delete removes it"
      (store/delete-segment! s uuid "seg-0.ts")
      (is (not (store/segment-exists? s uuid "seg-0.ts")))
      (is (nil? (store/open-segment s uuid "seg-0.ts"))))))

(deftest purge-channel-clears-all
  (let [base (temp-dir)
        s    (store/local-disk-store base)
        uuid "chan-2"]
    (store/put-segment! s uuid "seg-0.ts" (temp-file "a"))
    (store/put-segment! s uuid "seg-1.ts" (temp-file "b"))
    (is (store/segment-exists? s uuid "seg-1.ts"))
    (store/purge-channel! s uuid)
    (is (not (store/segment-exists? s uuid "seg-0.ts")))
    (is (not (.exists (io/file base uuid))))))

(deftest delete-missing-is-noop
  (let [s (store/local-disk-store (temp-dir))]
    (is (nil? (store/delete-segment! s "x" "absent.ts")))))
