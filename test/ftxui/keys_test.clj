(ns ftxui.keys-test
  "ftxui.keys maps key keywords to the shim's key codes and back. Pure."
  (:require [clojure.test :refer [deftest is testing]]
            [ftxui.keys :as keys]))

(deftest named-keys-round-trip
  (is (= 11 (keys/code :return)))
  (is (= :return (keys/keyword 11)))
  (is (= 13 (keys/code :tab)))
  (is (= 14 (keys/code :tab-reverse)))
  (is (= 1 (keys/code :arrow-left)))
  (is (= 20 (keys/code :f1)))
  (is (= 31 (keys/code :f12)))
  (is (= :page-down (keys/keyword 19))))

(deftest modifier-letters
  (testing "ctrl / alt / ctrl+alt letters are offset ranges"
    (is (= 101 (keys/code :ctrl-a)))
    (is (= :ctrl-z (keys/keyword 126)))
    (is (= 201 (keys/code :alt-a)))
    (is (= :alt-b (keys/keyword 202)))
    (is (= 301 (keys/code :ctrl-alt-a)))
    (is (= :ctrl-alt-c (keys/keyword 303)))))

(deftest unknowns
  (is (nil? (keys/keyword 0)))
  (is (nil? (keys/keyword 999)))
  (is (thrown? Exception (keys/code :bogus))))
