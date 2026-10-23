(ns pseudovision.media.connection-test
  (:require [clojure.test :refer [deftest is]]
            [pseudovision.media.connection :as sut]))

(deftest jellyfin-auth-headers-use-mediabrowser-authorization
  (is (= {"Authorization" "MediaBrowser Token=\"abc\""}
         (sut/jellyfin-auth-headers "abc"))))

(deftest jellyfin-stream-url-uses-apikey-not-legacy-api_key
  (let [url (sut/jellyfin-stream-url "http://jf:8096" "item1" "abc")]
    (is (= "http://jf:8096/Videos/item1/stream?static=true&ApiKey=abc" url))
    (is (not (re-find #"api_key" url)))))
