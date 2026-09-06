(ns ftxui.examples.showcase
  "A tour of the vocabulary: a menu on the left picks a page on the right.

  - elements: text styles, colors, borders, gauges, a spinner driven by a
    background thread (state in an ftxui.core/atom, so each tick redraws)
  - widgets: input, checkbox, radiobox, toggle, dropdown, slider, collapsible
  - layout: flexbox, gridbox, table
  - drawing: a canvas animated by the same ticker, and linear gradients
  - panes: a resizable split, a hoverable, and draggable floating windows
  - a modal dialog opened from a button and closed from inside it

  Run with `jolt showcase`. Escape exits (a root :on-event handler)."
  (:require [ftxui.core :as ui :refer [atom]]))

;; --- state -----------------------------------------------------------------------
(def page (atom 0))
(def tick (atom 0))
(def running (clojure.core/atom true))

(def form (atom {:name "" :agree false :size 1 :mode 0 :country 0 :volume 40 :details? false}))
(def dialog? (atom false))

(def split-size (atom 18))
(def hot (atom false))
(def windows (atom {:one {:left 1 :top 1 :width 26 :height 6}
                    :two {:left 10 :top 4 :width 28 :height 7}}))

(defn- set-field! [k] (fn [v] (swap! form assoc k v)))

;; --- pages -----------------------------------------------------------------------
(defn elements-page []
  [:vbox
   [:text {:bold true :underlined true} "Text styles"]
   [:hbox [:bold "bold "] [:dim "dim "] [:italic "italic "] [:inverted "inverted "]
    [:underlined "underlined "] [:strikethrough "strikethrough "] [:blink "blink"]]
   [:separator]
   [:text {:bold true :underlined true} "Colors"]
   [:hbox (for [c [:red :green :yellow :blue :magenta :cyan :white]] [:text {:color c} (name c) " "])]
   [:hbox [:text {:bg :blue :color :white} " on blue "] " "
    [:text {:color "#ff8800"} "#ff8800"] " "
    [:text {:color [:rgb 120 220 120]} "rgb"] " "
    [:text {:color 208} "palette 208"]]
   [:separator]
   [:text {:bold true :underlined true} "Borders"]
   [:hbox (for [s [:light :dashed :heavy :double :rounded]] [:border {:style s} (name s)])]
   [:separator]
   [:text {:bold true :underlined true} "Gauges & spinner"]
   [:hbox {:width 40} [:gauge {:value 0.3 :color :green}]]
   [:hbox {:width 40} [:gauge {:value (/ (mod @tick 100) 100.0) :color :cyan}]]
   [:hbox [:spinner {:charset 18 :index @tick}] " working… (tick " @tick ")"]
   [:separator]
   [:paragraph {:align :justify}
    "A paragraph wraps its words to the width it is given, and can be aligned left, right, center or justified."]])

(defn widgets-page []
  (let [{:keys [name agree size mode country volume details?]} @form]
    [:vbox
     [:hbox "Name:    " [:input {:value name :placeholder "your name" :flex true :on-change (set-field! :name)}]]
     [:checkbox {:label "I agree to the terms" :checked agree :on-change (set-field! :agree)}]
     [:separator]
     [:hbox
      [:vbox {:flex true}
       [:text {:bold true} "Radiobox"]
       [:radiobox {:entries ["small" "medium" "large"] :selected size :on-change (set-field! :size)}]]
      [:separator]
      [:vbox {:flex true}
       [:text {:bold true} "Toggle"]
       [:toggle {:entries ["light" "dark"] :selected mode :on-change (set-field! :mode)}]
       [:text {:bold true} "Dropdown"]
       [:dropdown {:entries ["Canada" "France" "Japan" "Peru"] :selected country :on-change (set-field! :country)}]]]
     [:separator]
     [:hbox "Volume " [:slider {:value volume :min 0 :max 100 :increment 5 :flex true :on-change (set-field! :volume)}]
      " " [:text {:width 3} (str volume)]]
     [:separator]
     [:collapsible {:label "Details" :show details? :on-change (set-field! :details?)}
      [:vbox {:border true}
       [:text "name:    " name]
       [:text "agree:   " (str agree)]
       [:text "size:    " (["small" "medium" "large"] size)]
       [:text "volume:  " volume]]]]))

(defn layout-page []
  [:vbox
   [:text {:bold true :underlined true} "flexbox"]
   [:flexbox {:gap [1 0] :wrap :wrap}
    (for [i (range 12)] [:border {:style :light} (str "item " i)])]
   [:separator]
   [:text {:bold true :underlined true} "gridbox"]
   [:gridbox {:rows [[[:text {:bold true} "a"] [:text "b"] [:text "c"]]
                     [[:text "d"] [:border "e"] [:text "f"]]]}]
   [:separator]
   [:text {:bold true :underlined true} "table"]
   [:table {:border :light :header true :separators :vertical
            :rows [["Name" "Language" "Stars"]
                   ["FTXUI" "C++" "8k"]
                   ["jolt" "Clojure" "∞"]
                   ["ftxui-jolt" "both" "you tell me"]]}]])

(defn drawing-page []
  (let [phase (* 0.15 @tick)
        wave  (for [x (range 44 100)]
                [:point x (int (+ 20 (* 14 (Math/sin (+ phase (/ x 7.0)))))) {:color :green}])]
    [:vbox
     [:text {:bold true :underlined true} "canvas — coordinates are pixels, 2x4 to a cell"]
     [:canvas {:width 100 :height 40
               :draw (into [[:circle 20 20 14 {:color :cyan}]
                            [:circle 20 20 6 {:filled true :color :magenta}]
                            [:line 40 0 40 39 {:color :gray-dark}]
                            [:text 44 0 "sin" {:color :yellow}]]
                           wave)}]
     [:separator]
     [:text {:bold true :underlined true} "gradients"]
     [:text {:color [:gradient :red :yellow :green :cyan :blue]} (apply str (repeat 46 "━"))]
     [:text {:bg {:angle 90 :stops [["#402060" 0.0] ["#20c0a0" 1.0]]} :color :white}
      " a background gradient, turned a quarter turn "]]))

(defn panes-page []
  [:vbox
   [:text {:bold true :underlined true} "resizable split — drag the separator"]
   [:vbox {:height 7}
    [:resizable-split {:direction :left :size @split-size :min 6 :max 50
                       :on-change #(reset! split-size %)}
     [:vbox {:border true} [:text "main"] [:text @split-size " cells wide"]]
     [:vbox {:border true} [:text "back"] [:text {:dim true} "takes the rest"]]]]
   [:separator]
   [:text {:bold true :underlined true} "hoverable — move the mouse over the box"]
   [:hoverable {:on-change #(reset! hot %)}
    [:text {:border true :bg (if @hot :blue :default) :color (if @hot :white :default)}
     (if @hot " the mouse is here " " hover me ")]]])

(defn- floating [k title]
  [:floating-window (assoc (@windows k) :title title :on-change #(swap! windows assoc k %))
   [:vbox [:text {:dim true} "drag me by the inside,"]
          [:text {:dim true} "resize me from an edge"]
          [:filler]
          [:text (pr-str (@windows k))]]])

(defn windows-page []
  [:vbox
   [:text {:bold true :underlined true} "floating windows — the one drawn on top takes the click"]
   [:stack {:flex true}
    [floating :one "one"]
    [floating :two "two"]]])

(defn modal-page []
  [:vbox
   [:text "A modal renders over the page and takes the focus while shown."]
   [:button {:label "Open dialog" :on-click #(reset! dialog? true)}]])

(def pages [["Elements" elements-page] ["Widgets" widgets-page] ["Layout" layout-page]
            ["Drawing" drawing-page] ["Panes" panes-page] ["Windows" windows-page]
            ["Modal" modal-page]])

;; --- app ----------------------------------------------------------------------------
(defn dialog []
  [:vbox {:border :double :bg :blue :color :white}
   [:text {:bold true} " Are you sure? "]
   [:separator]
   [:hbox
    [:button {:label "Yes" :on-click #(reset! dialog? false)}]
    [:button {:label "No" :on-click #(reset! dialog? false)}]]])

(defn app []
  [:modal {:show @dialog?}
   [:vbox {:border :rounded}
    ;; a :text concatenates its children as strings, so the dim part is a sibling
    [:hbox [:text {:bold true} " ftxui-jolt showcase "] [:dim "(Escape quits)"]]
    [:separator]
    [:hbox
     [:vbox {:width 14}
      [:menu {:entries (mapv first pages) :selected @page :on-change #(reset! page %)}]]
     [:separator]
     [:vbox {:flex true :frame true}
      [(second (pages @page))]]]]
   [dialog]])

(defn- ticker!
  "A background thread advancing `tick` ten times a second. Writing an
  ftxui.core/atom from another thread posts a redraw to the running loop."
  []
  (future
    (while @running
      (Thread/sleep 100)
      (swap! tick inc))))

(defn -main [& _]
  (reset! running true)
  (ticker!)
  (try
    (ui/run app :on-event (fn [e] (when (= :escape (:key e)) (ui/exit!) true)))
    (finally (reset! running false))))
