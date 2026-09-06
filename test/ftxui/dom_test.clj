(ns ftxui.dom-test
  "The element vocabulary: hiccup in, characters on a fixed-size screen out.
  Rendering is headless — FTXUI lays the DOM out on an in-memory screen and we
  read the cells back — so these need the shim but no terminal."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ftxui.core :as ui]))

(defn- lines [s] (mapv str/trimr (str/split-lines s)))

(deftest text-and-boxes
  (testing "a bare string, a :text, and numbers are text"
    (is (= "hello   " (ui/render-text "hello" 8 1)))
    (is (= "hello   " (ui/render-text [:text "hello"] 8 1)))
    (is (= "3    " (ui/render-text 3 5 1))))
  (testing ":text concatenates its children"
    (is (= "ab   " (ui/render-text [:text "a" "b"] 5 1)))
    (is (= "n=2  " (ui/render-text [:text "n=" 2] 5 1))))
  (testing "boxes"
    (is (= "a  \nb  " (ui/render-text [:vbox "a" "b"] 3 2)))
    (is (= "ab " (ui/render-text [:hbox "a" "b"] 3 1)))
    (is (= "ab\ncd" (ui/render-text [:vbox [:hbox "a" "b"] [:hbox "c" "d"]] 2 2)))))

(deftest children-splice-and-skip
  (testing "seqs are spliced, nils skipped"
    (is (= "a\nb\nc" (ui/render-text [:vbox (for [x ["a" "b"]] x) nil "c"] 1 3))))
  (testing "nested seqs splice too"
    (is (= "abc" (ui/render-text [:hbox (list (list "a" "b") "c")] 3 1)))))

(deftest function-components
  (let [hello (fn [n] [:text "hi " n])
        form2 (fn [] (fn [] "f2"))]
    (is (= "hi x" (ui/render-text [hello "x"] 4 1)))
    (is (= "f2" (ui/render-text [form2] 2 1)))
    (is (= "hi a\nf2  " (ui/render-text [:vbox [hello "a"] [form2]] 4 2)))))

(deftest borders-and-separators
  (testing "FTXUI's default border is rounded; styles are explicit"
    (is (= ["╭─╮" "│a│" "╰─╯"] (lines (ui/render-text [:border "a"] 3 3))))
    (is (= ["┌─┐" "│a│" "└─┘"] (lines (ui/render-text [:border {:style :light} "a"] 3 3))))
    (is (= ["╔═╗" "║a║" "╚═╝"] (lines (ui/render-text [:border {:style :double} "a"] 3 3)))))
  (testing "the same via the universal :border prop"
    (is (= ["╭─╮" "│a│" "╰─╯"] (lines (ui/render-text [:text {:border true} "a"] 3 3))))
    (is (= ["┌─┐" "│a│" "└─┘"] (lines (ui/render-text [:text {:border :light} "a"] 3 3)))))
  (testing "a separator takes its orientation from the box it gets"
    (is (= ["a" "───" "b"] (lines (ui/render-text [:vbox "a" [:separator] "b"] 3 3))))
    (is (= ["a" "╍╍╍" "b"] (lines (ui/render-text [:vbox "a" [:separator {:style :dashed}] "b"] 3 3))))
    (is (= "a│b" (ui/render-text [:hbox "a" [:separator] "b"] 3 1))))
  (is (= "a|b" (ui/render-text [:hbox "a" [:separator {:char "|"}] "b"] 3 1)))
  (testing "a window is a border with a title"
    (is (= ["╭T──╮" "│a  │" "╰───╯"] (lines (ui/render-text [:window {:title "T"} "a"] 5 3))))))

(deftest sizing-flex-and-alignment
  (is (= "a   b" (ui/render-text [:hbox "a" [:filler] "b"] 5 1)))
  (is (= "abc|  " (ui/render-text [:hbox [:text {:width 3} "abcdef"] "|"] 6 1)))
  (is (= "ab|   " (ui/render-text [:hbox [:text {:width [:<= 2]} "abcdef"] "|"] 6 1)))
  (is (= "a    |" (ui/render-text [:hbox [:text {:width [:>= 5]} "a"] "|"] 6 1)))
  (is (= "a    |" (ui/render-text [:hbox [:text {:flex true} "a"] "|"] 6 1)))
  (is (= "  a  " (ui/render-text [:hcenter "a"] 5 1)))
  (is (= "  a  " (ui/render-text [:text {:align :center} "a"] 5 1)))
  (is (= "    a" (ui/render-text [:align-right "a"] 5 1)))
  (is (= ["" " a" ""] (lines (ui/render-text [:center "a"] 3 3)))))

(deftest decorator-tags-take-many-children
  (testing "a decorator over several children lays them out as an hbox"
    (is (= "ab" (ui/render-text [:bold "a" "b"] 2 1)))
    (is (= ["╭──╮" "│ab│" "╰──╯"] (lines (ui/render-text [:border "a" "b"] 4 3))))))

(deftest text-styles-reach-the-terminal
  (testing "styles are invisible in plain text but present in the escape stream"
    (is (= "a" (ui/render-text [:bold "a"] 1 1)))
    (is (str/includes? (ui/render-ansi [:bold "a"] 1 1) "[1m"))
    (is (str/includes? (ui/render-ansi [:text {:bold true} "a"] 1 1) "[1m"))
    (is (str/includes? (ui/render-ansi [:text {:color :red} "a"] 1 1) "[31m"))
    (is (str/includes? (ui/render-ansi [:text {:bg [:rgb 1 2 3]} "a"] 1 1) "48;2;1;2;3"))))

(deftest paragraph-gauge-and-grids
  (is (= ["aa bb" "cc"] (lines (ui/render-text [:paragraph "aa bb cc"] 5 2))))
  (is (= 2 (count (re-seq #"█" (ui/render-text [:gauge {:value 0.5}] 4 1)))))
  (is (= ["ab" "cd"] (lines (ui/render-text [:gridbox {:rows [["a" "b"] ["c" "d"]]}] 2 2))))
  (testing "a table draws its rows with borders and separators"
    (let [t (ui/render-text [:table {:rows [["h" "i"] ["a" "b"]] :border :light :separators :both}] 5 5)]
      (is (= ["┌─┬─┐" "│h│i│" "├─┼─┤" "│a│b│" "└─┴─┘"] (lines t))))))

(deftest canvas-draws-in-pixels
  (testing "coordinates are pixels: a cell holds 2x4 of them, so 4x8 is 2x2 cells"
    (is (= ["" ""] (lines (ui/render-text [:canvas {:width 4 :height 8}] 2 2)))))
  (testing "points, in braille (1x1 per pixel) and in blocks (2x2)"
    (is (= "⠁" (ui/render-text [:canvas {:width 2 :height 4 :draw [[:point 0 0]]}] 1 1)))
    (is (= "⢁" (ui/render-text [:canvas {:width 2 :height 4 :draw [[:point 0 0] [:point 1 3]]}] 1 1)))
    (is (= "▘" (ui/render-text [:canvas {:width 2 :height 4 :draw [[:point 0 0 {:style :block}]]}] 1 1))))
  (testing "the canvas :style is the default every op inherits"
    (is (= "▘" (ui/render-text [:canvas {:width 2 :height 4 :style :block :draw [[:point 0 0]]}] 1 1))))
  (testing ":value turns a point off, or toggles it — a drawn cell stays braille"
    (is (= "⠀" (ui/render-text [:canvas {:width 2 :height 4
                                         :draw [[:point 0 0] [:point 0 0 {:value :toggle}]]}] 1 1)))
    (is (= "⠀" (ui/render-text [:canvas {:width 2 :height 4
                                         :draw [[:point 0 0] [:point 0 0 {:value false}]]}] 1 1))))
  (testing "lines, shapes and text"
    (is (= "⠉⠉⠉⠉" (ui/render-text [:canvas {:width 8 :height 4 :draw [[:line 0 0 7 0]]}] 4 1)))
    (is (= "hi" (str/trim (ui/render-text [:canvas {:width 8 :height 4 :draw [[:text 0 0 "hi"]]}] 4 1))))
    (is (= ["  ⢀⣤⣤⣄"
            " ⣴⣿⣿⣿⣿⣷⡄"
            " ⢿⣿⣿⣿⣿⣿⠇"
            "  ⠙⠿⠿⠟⠁"]
           (lines (ui/render-text [:canvas {:width 16 :height 16 :draw [[:circle 8 8 6 {:filled true}]]}] 8 4)))))
  (testing "an op color reaches the terminal"
    (is (str/includes? (ui/render-ansi [:canvas {:width 2 :height 4 :draw [[:point 0 0 {:color :red}]]}] 1 1)
                       "[31m")))
  (testing "an unknown op throws"
    (is (thrown? Exception (ui/render-text [:canvas {:draw [[:blob 1 2]]}] 2 2)))))

(deftest gradients-stand-in-for-colors
  (testing "a foreground gradient walks from one stop to the next across the element"
    (let [ansi (ui/render-ansi [:text {:color [:gradient :red :blue]} "abcd"] 4 1)]
      (is (str/includes? ansi "38;2;127;0;0"))
      (is (str/includes? ansi "38;2;0;0;127"))
      (is (= 4 (count (distinct (re-seq #"38;2;\d+;\d+;\d+" ansi))))
          "every cell gets its own step")))
  (testing "the map form places its stops and takes an angle"
    (is (= ["48;2;0;0;127" "48;2;127;0;0"]
           (re-seq #"48;2;[0-9;]+" (ui/render-ansi [:text {:bg {:angle 90 :stops [[:red 0.0] [:blue 1.0]]}} "ab"] 2 1)))
        "at 90 degrees the gradient runs the other way"))
  (testing "widgets take one too, through the same universal prop"
    (is (str/includes? (ui/render-ansi [:button {:label "b" :style :ascii :color [:gradient :red :blue]}] 3 1)
                       "38;2;"))))

(deftest unknown-tags-throw
  (is (thrown? Exception (ui/render-text [:no-such-tag "x"] 3 1))))
