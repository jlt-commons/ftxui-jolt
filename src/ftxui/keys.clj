(ns ftxui.keys
  "Key names <-> the shim's key codes, and the decoding of an fj_event into
  the map an :on-event handler receives:

    {:type :key       :key :arrow-down :input \"\\e[B\"}
    {:type :character :char \"q\"      :input \"q\"}
    {:type :mouse     :button :left :motion :pressed :x 3 :y 4
                      :shift false :meta false :control false}
    {:type :custom}
    {:type :unknown   :input \"...\"}

  Modifier letters are :ctrl-a .. :ctrl-z, :alt-a .. :alt-z and
  :ctrl-alt-a .. :ctrl-alt-z."
  (:refer-clojure :exclude [keyword])
  (:require [ftxui.ffi :as f]))

(def ^:private named
  [:arrow-left :arrow-right :arrow-up :arrow-down
   :arrow-left-ctrl :arrow-right-ctrl :arrow-up-ctrl :arrow-down-ctrl
   :backspace :delete :return :escape :tab :tab-reverse :insert :home :end
   :page-up :page-down])

(def ^:private letters "abcdefghijklmnopqrstuvwxyz")

(def ^:private key->code
  (merge (zipmap named (map inc (range)))                                 ; 1..19
         (zipmap (map #(clojure.core/keyword (str "f" %)) (range 1 13))
                 (range 20 32))                                          ; 20..31
         (zipmap (map #(clojure.core/keyword (str "ctrl-" %)) letters) (range 101 127))
         (zipmap (map #(clojure.core/keyword (str "alt-" %)) letters) (range 201 227))
         (zipmap (map #(clojure.core/keyword (str "ctrl-alt-" %)) letters) (range 301 327))))

(def ^:private code->key (into {} (map (fn [[k v]] [v k])) key->code))

(defn code
  "The shim's code for key keyword `k`. Throws on an unknown name."
  [k]
  (or (key->code k)
      (throw (ex-info (str "keys: unknown key " k) {:key k :known (sort (keys key->code))}))))

(defn keyword
  "The key keyword for a shim code, or nil."
  [c]
  (code->key c))

(def ^:private buttons [:left :middle :right :none :wheel-up :wheel-down :wheel-left :wheel-right])
(def ^:private motions [:released :pressed :moved])

(defn button-code [b]
  (or (some (fn [[i k]] (when (= k b) i)) (map-indexed vector buttons))
      (throw (ex-info (str "keys: unknown mouse button " b) {:button b}))))

(defn motion-code [m]
  (or (some (fn [[i k]] (when (= k m) i)) (map-indexed vector motions))
      (throw (ex-info (str "keys: unknown mouse motion " m) {:motion m}))))

(defn decode
  "Decode the fj_event at `ptr` into an event map."
  [ptr]
  (let [{:keys [type key button motion shift meta control x y input]} (f/read-event ptr)]
    (case type
      1 {:type :character :char input :input input}
      2 {:type :mouse :button (get buttons button :none) :motion (get motions motion :pressed)
         :x x :y y :shift (= 1 shift) :meta (= 1 meta) :control (= 1 control)}
      3 {:type :custom}
      4 {:type :key :key (keyword key) :input input}
      {:type :unknown :input input})))
