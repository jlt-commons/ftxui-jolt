(ns ftxui.color
  "The color vocabulary, encoded as the one int the shim decodes:

    0                 default (the terminal's own)
    1..16             the 16 ANSI colors, palette index + 1
    0x100 | i         256-color palette index i
    0x1000000 | rgb   true color

  Accepted forms: a keyword naming an ANSI color (:red, :gray-dark,
  :blue-light ...), a 0-255 int (256-palette index), [:p256 i], [:rgb r g b],
  a hex string (\"#ff8800\" or \"#f80\"), or an already-encoded int.

  A linear gradient stands in for a color wherever one is accepted; it needs
  more than one int, so it stays a spec here and ftxui.dom builds it."
  (:require [clojure.string :as str]))

(def palette16
  "ANSI color name -> FTXUI Palette16 index."
  {:black 0 :red 1 :green 2 :yellow 3 :blue 4 :magenta 5 :cyan 6 :gray-light 7
   :gray-dark 8 :red-light 9 :green-light 10 :yellow-light 11 :blue-light 12
   :magenta-light 13 :cyan-light 14 :white 15})

(def ^:private P256 0x100)
(def ^:private RGB 0x1000000)

(defn rgb
  "Encode a true color from 0-255 components."
  [r g b]
  (bit-or RGB (bit-shift-left (bit-and r 0xff) 16) (bit-shift-left (bit-and g 0xff) 8) (bit-and b 0xff)))

(defn- hex-digit [c]
  (let [i (str/index-of "0123456789abcdef" (str/lower-case (str c)))]
    (when-not i (throw (ex-info (str "color: bad hex digit " (pr-str c)) {:char c})))
    i))

(defn- hex [s]
  (let [digits (subs s 1)
        n (count digits)]
    (case n
      3 (let [[r g b] (map hex-digit digits)]
          (rgb (* r 17) (* g 17) (* b 17)))
      6 (let [[r1 r2 g1 g2 b1 b2] (map hex-digit digits)]
          (rgb (+ (* 16 r1) r2) (+ (* 16 g1) g2) (+ (* 16 b1) b2)))
      (throw (ex-info (str "color: expected #rgb or #rrggbb, got " (pr-str s)) {:color s})))))

(defn code
  "Encode a color for the shim. nil and :default are the terminal default."
  [c]
  (cond
    (nil? c)        0
    (= :default c)  0
    (keyword? c)    (if-let [i (palette16 c)]
                      (inc i)
                      (throw (ex-info (str "color: unknown color " c) {:color c})))
    (integer? c)    (cond (< c 0) (throw (ex-info "color: negative" {:color c}))
                          (< c 256) (bit-or P256 c)
                          :else c)
    (string? c)     (if (str/starts-with? c "#")
                      (hex c)
                      (throw (ex-info (str "color: expected a #hex string, got " (pr-str c)) {:color c})))
    (vector? c)     (case (first c)
                      :rgb  (if (= 4 (count c))
                              (apply rgb (rest c))
                              (throw (ex-info "color: [:rgb r g b] takes three components" {:color c})))
                      :p256 (bit-or P256 (bit-and (second c) 0xff))
                      (throw (ex-info (str "color: unknown form " (pr-str c)) {:color c})))
    :else (throw (ex-info (str "color: unsupported " (pr-str c)) {:color c}))))

;; --- linear gradients -----------------------------------------------------------
(defn gradient?
  "Is `c` a gradient rather than a plain color? Either [:gradient c1 c2 ...]
  or {:angle degrees :stops [...]}."
  [c]
  (or (and (map? c) (contains? c :stops))
      (and (vector? c) (= :gradient (first c)))))

(defn- stop
  "A stop is a color, or a [color position] pair with a 0-1 position. Only the
  color forms that are themselves vectors need telling apart."
  [s]
  (if (and (vector? s) (not (#{:rgb :p256} (first s))))
    [(code (first s)) (some-> (second s) double)]
    [(code s) nil]))

(defn gradient
  "Normalize a gradient spec to {:angle degrees :stops [[code position] ...]}.
  A nil position leaves the stop for FTXUI to place, which spreads the
  unplaced ones evenly between their neighbours."
  [c]
  (when-not (gradient? c)
    (throw (ex-info (str "color: not a gradient " (pr-str c)) {:color c})))
  (let [{:keys [angle stops]} (if (map? c) c {:stops (rest c)})]
    {:angle (double (or angle 0))
     :stops (mapv stop stops)}))
