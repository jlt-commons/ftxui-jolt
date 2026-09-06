(ns ftxui.color-test
  "ftxui.color/code turns the color vocabulary into the one-int encoding the
  shim decodes (see native/ftxui_jolt.h). Pure — no native library needed."
  (:require [clojure.test :refer [deftest is testing]]
            [ftxui.color :as color]))

(deftest palette16-by-keyword
  (testing "default / nil is transparent"
    (is (= 0 (color/code :default)))
    (is (= 0 (color/code nil))))
  (testing "the 16 ANSI colors are palette index + 1"
    (is (= 1 (color/code :black)))
    (is (= 2 (color/code :red)))
    (is (= 5 (color/code :blue)))
    (is (= 8 (color/code :gray-light)))
    (is (= 9 (color/code :gray-dark)))
    (is (= 10 (color/code :red-light)))
    (is (= 16 (color/code :white)))))

(deftest palette256-and-truecolor
  (testing "a 256-palette index, explicitly or as a bare int"
    (is (= (bit-or 0x100 208) (color/code [:p256 208])))
    (is (= (bit-or 0x100 208) (color/code 208))))
  (testing "rgb triples and hex strings"
    (is (= (bit-or 0x1000000 0xff0000) (color/code [:rgb 255 0 0])))
    (is (= (bit-or 0x1000000 0x123456) (color/code "#123456")))
    (is (= (bit-or 0x1000000 0x112233) (color/code "#123"))))
  (testing "an already-encoded int passes through"
    (is (= (bit-or 0x1000000 0x0000ff) (color/code (bit-or 0x1000000 0x0000ff))))))

(deftest unknown-colors-throw
  (is (thrown? Exception (color/code :mauve)))
  (is (thrown? Exception (color/code "#12")))
  (is (thrown? Exception (color/code [:rgb 1 2]))))

(deftest gradients-are-specs-not-codes
  (testing "what counts as a gradient"
    (is (color/gradient? [:gradient :red :blue]))
    (is (color/gradient? {:angle 45 :stops [:red :blue]}))
    (is (not (color/gradient? :red)))
    (is (not (color/gradient? [:rgb 1 2 3])))
    (is (not (color/gradient? {:style :light}))))
  (testing "the vector form: colors only, left for FTXUI to place"
    (is (= {:angle 0.0 :stops [[2 nil] [5 nil]]}
           (color/gradient [:gradient :red :blue]))))
  (testing "the map form takes an angle, and a stop may carry a position"
    (is (= {:angle 45.0 :stops [[2 0.0] [5 1.0]]}
           (color/gradient {:angle 45 :stops [[:red 0] [:blue 1]]}))))
  (testing "a stop that is itself a color vector is not a [color position] pair"
    (is (= {:angle 0.0 :stops [[(bit-or 0x1000000 0xff0000) nil] [(bit-or 0x100 8) nil]]}
           (color/gradient [:gradient [:rgb 255 0 0] [:p256 8]]))))
  (is (thrown? Exception (color/gradient :red))))
