(ns ftxui.dom
  "The element vocabulary: hiccup tags for FTXUI's DOM, and the build step
  that turns a prepared tree into element handles every frame.

  FTXUI rebuilds its DOM on every render, so there is nothing to reconcile
  here: build walks the prepared tree (see ftxui.render) and calls one shim
  function per node. Interactive components appear in the tree as
  {:type :component :id n} and render through their FTXUI component.

  Every element also accepts the universal decorator props (:bold, :color,
  :border, :width, :flex, :align, ...); the decorator tags (:bold, :border,
  :center, ...) are sugar that sets the same prop on an implicit wrapper."
  (:require [ftxui.ffi :as f]
            [ftxui.color :as color]))

;; --- enum tables ---------------------------------------------------------------
(def border-styles {:light 0 :dashed 1 :heavy 2 :double 3 :rounded 4 :empty 5})
(def text-styles [[:bold 0] [:dim 1] [:italic 2] [:inverted 3] [:underlined 4]
                  [:underlined-double 5] [:blink 6] [:strikethrough 7]])
(def flex-kinds {true 0 :flex 0 :grow 1 :shrink 2 :x 3 :x-grow 4 :x-shrink 5
                 :y 6 :y-grow 7 :y-shrink 8 :none 9})
(def frame-kinds {true 0 :frame 0 :x 1 :y 2})
(def focus-shapes {true 0 :focus 0 :block 1 :block-blinking 2 :bar 3 :bar-blinking 4
                   :underline 5 :underline-blinking 6})
(def aligns {:center 0 :hcenter 1 :vcenter 2 :right 3})
(def directions {:up 0 :down 1 :left 2 :right 3})
(def paragraph-aligns {nil 0 :left 1 :right 2 :center 3 :justify 4})
(def flex-directions {:row 0 :row-inversed 1 :column 2 :column-inversed 3})
(def flex-wraps {:no-wrap 0 :wrap 1 :wrap-inversed 2})
(def flex-justify {:flex-start 0 :start 0 :flex-end 1 :end 1 :center 2 :stretch 3
                   :space-between 4 :space-around 5 :space-evenly 6})
(def separators {:vertical 1 :horizontal 2 :both 3})

(defn- lookup [table x what]
  (or (get table x)
      (throw (ex-info (str "dom: unknown " what " " (pr-str x)) {:value x :known (keys table)}))))

(defn- border-style [s]
  (if (or (nil? s) (true? s)) -1 (lookup border-styles s "border style")))

;; --- universal decorator props -------------------------------------------------
(defn- size-spec
  "n | [:<= n] | [:>= n] | [:= n] -> [constraint value]"
  [x]
  (cond
    (number? x) [1 (int x)]
    (vector? x) [(case (first x) :<= 0 := 1 :>= 2
                   (throw (ex-info (str "dom: size constraint is :<=, := or :>=, got " (first x)) {:size x})))
                 (int (second x))]
    :else (throw (ex-info (str "dom: bad size " (pr-str x)) {:size x}))))

(defn- apply-border [h b]
  (let [{:keys [style color]} (if (map? b) b {:style b})]
    (f/border h (border-style style) (color/code color))))

(defn decorate
  "Apply the universal decorator props in `props` to element handle `h`,
  inner to outer: text styles, colors, border, size, flex, frame, scroll
  indicator, alignment, focus, then the rest."
  [h props]
  (if (empty? props)
    h
    (as-> h h
      (reduce (fn [h [k code]] (if (get props k) (f/style h code) h)) h text-styles)
      (if-some [c (:color props)] (f/color h (color/code c)) h)
      (if-some [c (:bg props)] (f/bgcolor h (color/code c)) h)
      (if-some [b (:border props)] (if b (apply-border h b) h) h)
      (if-some [w (:width props)] (let [[c v] (size-spec w)] (f/size h 0 c v)) h)
      (if-some [hh (:height props)] (let [[c v] (size-spec hh)] (f/size h 1 c v)) h)
      (if-some [fl (:flex props)] (if fl (f/flex h (lookup flex-kinds fl "flex kind")) h) h)
      (if-some [fr (:frame props)] (if fr (f/frame h (lookup frame-kinds fr "frame kind")) h) h)
      (if-some [si (:scroll-indicator props)]
        (if si (f/scroll-indicator h (if (= :h si) 1 0)) h) h)
      (if-some [a (:align props)] (f/align h (lookup aligns a "alignment")) h)
      (if-some [fo (:focus props)] (if fo (f/focus h (lookup focus-shapes fo "focus shape")) h) h)
      (if (:clear-under props) (f/clear-under h) h)
      (if-some [u (:hyperlink props)] (f/hyperlink h (str u)) h)
      (if (:automerge props) (f/automerge h) h))))

;; --- tags ------------------------------------------------------------------------
;; Each tag has a :kind that tells the prepare step (ftxui.render) what its
;; children are:
;;   :text    children are strings, concatenated into :text
;;   :leaf    no children
;;   :layout  children are elements, laid out by the tag
;;   :wrap    one child decorated; several are laid out as an hbox first
;;   :rows    a :rows prop of rows of cells
;; :build takes the prepared node and the recursive build fn and returns a
;; handle. :consumes lists props the tag interprets itself so they are not
;; also applied as universal decorators. :props translates a decorator tag's
;; own props into universal ones. :hiccup-props name props holding hiccup
;; (prepared by ftxui.render into :prepared-props).

(defn- wrap-children [node build]
  (let [hs (mapv build (:children node))]
    (case (count hs)
      0 (f/empty)
      1 (first hs)
      (f/hbox hs))))

(defn- flexbox-build [node build]
  (let [p (:props node)
        [gx gy] (let [g (:gap p 0)] (if (vector? g) g [g g]))]
    (f/flexbox (mapv build (:children node))
               (lookup flex-directions (:direction p :row) "flexbox direction")
               (lookup flex-wraps (:wrap p :wrap) "flexbox wrap")
               (lookup flex-justify (:justify p :flex-start) "justify-content")
               (min 3 (lookup flex-justify (:align-items p :flex-start) "align-items"))
               (lookup flex-justify (:align-content p :flex-start) "align-content")
               (int gx) (int gy))))

(defn- cells-of
  "Flatten prepared :rows into row-major handles, padding short rows with 0."
  [rows build]
  (let [cols (reduce max 0 (map count rows))
        cells (into [] (mapcat (fn [row] (into (mapv build row) (repeat (- cols (count row)) 0)))) rows)]
    [cells cols (count rows)]))

(defn- gridbox-build [node build]
  (let [[cells cols rows] (cells-of (:rows node) build)]
    (f/gridbox cells cols rows)))

(defn- table-build [node build]
  (let [p (:props node)
        [cells cols rows] (cells-of (:rows node) build)
        b (:border p)]
    (f/table cells cols rows
             (if (or (nil? b) (false? b)) -1 (border-style b))
             (if (:header p) 1 0)
             (if-some [s (:separators p)] (if s (lookup separators s "table separators") 0) 0))))

(defn- text-build [node _] (f/text (:text node)))

(def ^:private layout
  {:kind :layout :consumes []})

(defn- wrap-tag
  "A decorator tag: a :wrap whose props map to universal props via `props-fn`."
  ([prop-key] (wrap-tag prop-key (constantly true)))
  ([prop-key value-fn]
   {:kind :wrap :consumes []
    :props (fn [p] (assoc p prop-key (value-fn p)))
    :build wrap-children}))

(def tags
  {;; text
   :text      {:kind :text :consumes [] :build text-build}
   :vtext     {:kind :text :consumes [] :build (fn [node _] (f/vtext (:text node)))}
   :paragraph {:kind :text :consumes [:align]
               :build (fn [node _] (f/paragraph (:text node) (lookup paragraph-aligns (:align (:props node)) "paragraph alignment")))}
   ;; leaves
   :separator {:kind :leaf :consumes [:style :char]
               :build (fn [node _] (let [p (:props node)]
                                     (if-some [c (:char p)] (f/separator-char (str c)) (f/separator (border-style (:style p))))))}
   :gauge     {:kind :leaf :consumes [:value :direction]
               :build (fn [node _] (let [p (:props node)]
                                     (f/gauge (double (:value p 0)) (lookup directions (:direction p :right) "direction"))))}
   :spinner   {:kind :leaf :consumes [:charset :index]
               :build (fn [node _] (let [p (:props node)] (f/spinner (int (:charset p 0)) (int (:index p 0)))))}
   :filler    {:kind :leaf :consumes [] :build (fn [_ _] (f/filler))}
   :empty     {:kind :leaf :consumes [] :build (fn [_ _] (f/empty))}
   ;; layouts
   :hbox      (assoc layout :container :horizontal :build (fn [node build] (f/hbox (mapv build (:children node)))))
   :vbox      (assoc layout :container :vertical   :build (fn [node build] (f/vbox (mapv build (:children node)))))
   :dbox      (assoc layout :container :vertical   :build (fn [node build] (f/dbox (mapv build (:children node)))))
   :hflow     (assoc layout :container :horizontal :build (fn [node build] (f/hflow (mapv build (:children node)))))
   :vflow     (assoc layout :container :vertical   :build (fn [node build] (f/vflow (mapv build (:children node)))))
   :flexbox   (assoc layout :container :vertical :build flexbox-build
                     :consumes [:direction :wrap :justify :align-items :align-content :gap])
   :gridbox   {:kind :rows :container :vertical :consumes [:rows] :build gridbox-build}
   :table     {:kind :rows :container :vertical :consumes [:rows :border :header :separators] :build table-build}
   ;; wrappers with their own props
   :border    {:kind :wrap :consumes [] :build wrap-children
               :props (fn [p] (-> p (dissoc :style :color)
                                  (assoc :border {:style (:style p) :color (:color p)})))}
   :window    {:kind :wrap :consumes [:title :style] :hiccup-props [:title]
               :build (fn [node build]
                        (f/window (build (get-in node [:prepared-props :title]))
                                  (wrap-children node build)
                                  (let [s (:style (:props node))] (if s (border-style s) -1))))}
   :style     {:kind :wrap :consumes [] :build wrap-children}
   :color     {:kind :wrap :consumes [] :build wrap-children
               :props (fn [p] (-> p (dissoc :fg) (cond-> (:fg p) (assoc :color (:fg p)))))}
   :size      {:kind :wrap :consumes [] :build wrap-children}
   :hyperlink {:kind :wrap :consumes [] :build wrap-children
               :props (fn [p] (-> p (dissoc :url) (assoc :hyperlink (:url p))))}
   ;; decorator sugar
   :bold (wrap-tag :bold) :dim (wrap-tag :dim) :italic (wrap-tag :italic)
   :inverted (wrap-tag :inverted) :underlined (wrap-tag :underlined)
   :underlined-double (wrap-tag :underlined-double) :blink (wrap-tag :blink)
   :strikethrough (wrap-tag :strikethrough)
   :flex (wrap-tag :flex) :flex-grow (wrap-tag :flex (constantly :grow))
   :flex-shrink (wrap-tag :flex (constantly :shrink))
   :xflex (wrap-tag :flex (constantly :x)) :yflex (wrap-tag :flex (constantly :y))
   :xflex-grow (wrap-tag :flex (constantly :x-grow)) :xflex-shrink (wrap-tag :flex (constantly :x-shrink))
   :yflex-grow (wrap-tag :flex (constantly :y-grow)) :yflex-shrink (wrap-tag :flex (constantly :y-shrink))
   :notflex (wrap-tag :flex (constantly :none))
   :frame (wrap-tag :frame) :xframe (wrap-tag :frame (constantly :x)) :yframe (wrap-tag :frame (constantly :y))
   :focus (wrap-tag :focus)
   :center (wrap-tag :align (constantly :center)) :hcenter (wrap-tag :align (constantly :hcenter))
   :vcenter (wrap-tag :align (constantly :vcenter)) :align-right (wrap-tag :align (constantly :right))
   :clear-under (wrap-tag :clear-under) :automerge (wrap-tag :automerge)
   :vscroll-indicator (wrap-tag :scroll-indicator (constantly :v))
   :hscroll-indicator (wrap-tag :scroll-indicator (constantly :h))})

(defn tag-info
  "The tag table entry for `tag`, or nil when it is not an element tag."
  [tag]
  (get tags tag))

(defn element-tag? [tag] (contains? tags tag))

;; --- build --------------------------------------------------------------------
(defn build
  "Turn a prepared node into an element handle (valid for this frame)."
  [node]
  (case (:type node)
    :text      (f/text (:text node))
    :component (decorate (f/component-render (:id node)) (:props node))
    :element   (let [{:keys [tag props]} node
                     info (tag-info tag)
                     h ((:build info) node build)]
                 (decorate h (apply dissoc props (:consumes info))))
    (throw (ex-info (str "dom: cannot build " (pr-str node)) {:node node}))))
