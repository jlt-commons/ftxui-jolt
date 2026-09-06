(ns ftxui.render-test
  "Interactive components, driven headlessly: mount a component, deliver key
  and character events straight to the FTXUI component tree, and read the
  next frame back as text. Covers focus routing through the layout, the
  controlled-prop contract, component identity across frames, and wrappers."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ftxui.core :as ui :refer [with-screen]]))

(defn- lines [s] (mapv str/trimr (str/split-lines s)))

(defn- ascii-button [label on-click]
  [:button {:label label :style :ascii :on-click on-click}])

(deftest button-click-updates-state
  (let [count (atom 0)
        app   (fn [] [:vbox [:text "Count: " @count]
                            (ascii-button "+1" #(swap! count inc))])]
    (with-screen [s app]
      (testing "the only button starts focused: [label]"
        (is (= ["Count: 0" "[+1]"] (lines (ui/render-text s 10 2)))))
      (ui/send-key! s :return)
      (is (= 1 @count))
      (is (= ["Count: 1" "[+1]"] (lines (ui/render-text s 10 2)))))))

(deftest tab-moves-focus-between-siblings
  (let [hits (atom [])
        app  (fn [] [:hbox (ascii-button "a" #(swap! hits conj :a))
                           (ascii-button "b" #(swap! hits conj :b))])]
    (with-screen [s app]
      (is (= "[a] b " (ui/render-text s 6 1)))
      (ui/send-key! s :tab)
      (is (= " a [b]" (ui/render-text s 6 1)))
      (ui/send-key! s :return)
      (is (= [:b] @hits))
      (ui/send-key! s :arrow-left)
      (ui/send-key! s :return)
      (is (= [:b :a] @hits)))))

(deftest layout-is-the-focus-tree
  (testing "vbox of hboxes: arrows move between rows and within a row"
    (let [hits (atom [])
          b    (fn [k] (ascii-button (name k) #(swap! hits conj k)))
          app  (fn [] [:vbox [:hbox (b :a) (b :b)] [:hbox (b :c) (b :d)]])]
      (with-screen [s app]
        (ui/send-key! s :arrow-down)
        (ui/send-key! s :arrow-right)
        (ui/send-key! s :return)
        (is (= [:d] @hits))
        (ui/send-key! s :arrow-up)
        (ui/send-key! s :return)
        (is (= [:d :a] @hits) "the first row kept its own selection"))))
  (testing "non-layout wrappers pass focus through"
    (let [hits (atom [])
          app  (fn [] [:border [:vbox [:text "t"]
                                     [:bold (ascii-button "x" #(swap! hits conj :x))]]])]
      (with-screen [s app]
        (ui/send-key! s :return)
        (is (= [:x] @hits))))))

(deftest input-is-controlled-by-its-value-prop
  (let [text (atom "")
        seen (atom [])
        app  (fn [] [:input {:value @text :placeholder "type"
                             :on-change (fn [v] (swap! seen conj v) (reset! text v))}])]
    (with-screen [s app]
      (ui/send-char! s "a")
      (ui/send-char! s "b")
      (is (= ["a" "ab"] @seen))
      (is (= "ab" @text))
      (is (str/starts-with? (ui/render-text s 6 1) "ab"))
      (testing "a programmatic change flows into the widget"
        (reset! text "z")
        (is (str/starts-with? (ui/render-text s 6 1) "z "))))))

(deftest input-reverts-when-the-handler-rejects
  (let [text (atom "")
        app  (fn [] [:input {:value @text :placeholder "p" :on-change (fn [_])}])]
    (with-screen [s app]
      (ui/send-char! s "a")
      (is (str/starts-with? (ui/render-text s 6 1) "p") "back to the placeholder"))))

(deftest input-enter-and-uncontrolled
  (let [entered (atom nil)
        app     (fn [] [:input {:on-enter #(reset! entered %)}])]
    (with-screen [s app]
      (ui/send-char! s "h")
      (ui/send-char! s "i")
      (ui/send-key! s :return)
      (is (= "hi" @entered))
      (is (str/starts-with? (ui/render-text s 6 1) "hi") "without :value the widget keeps its own text"))))

(deftest checkbox-toggles
  (let [on  (atom false)
        app (fn [] [:checkbox {:label "done" :checked @on :on-change #(reset! on %)}])]
    (with-screen [s app]
      (is (str/includes? (ui/render-text s 8 1) "done"))
      (ui/send-key! s :return)
      (is (true? @on))
      (ui/send-key! s :return)
      (is (false? @on)))))

(deftest menu-selection
  (let [sel     (atom 0)
        entered (atom nil)
        app     (fn [] [:menu {:entries ["one" "two" "three"] :selected @sel
                               :on-change #(reset! sel %) :on-enter #(reset! entered %)}])]
    (with-screen [s app]
      (is (= ["> one" "  two" "  three"] (lines (ui/render-text s 8 3))))
      (ui/send-key! s :arrow-down)
      (is (= 1 @sel))
      (is (= ["  one" "> two" "  three"] (lines (ui/render-text s 8 3))))
      (ui/send-key! s :return)
      (is (= 1 @entered))
      (testing "the :selected prop drives the widget"
        (reset! sel 2)
        (is (= ["  one" "  two" "> three"] (lines (ui/render-text s 8 3))))))))

(deftest radiobox-and-slider
  (let [sel (atom 0)
        val (atom 3)
        app (fn [] [:vbox [:radiobox {:entries ["a" "b"] :selected @sel :on-change #(reset! sel %)}]
                          [:slider {:value @val :min 0 :max 10 :increment 1 :on-change #(reset! val %)}]])]
    (with-screen [s app]
      (ui/send-key! s :arrow-down)
      (ui/send-key! s :return)
      (is (= 1 @sel))
      ;; a radiobox consumes Tab to cycle its entries; ArrowDown past the
      ;; last entry is what hands focus to the next sibling
      (ui/send-key! s :arrow-down)
      (ui/send-key! s :arrow-right)
      (is (= 4 @val)))))

(deftest form-2-keeps-local-state
  (let [comp (fn [] (let [n (atom 0)]
                      (fn [] (ascii-button (str "n=" @n) #(swap! n inc)))))]
    (with-screen [s comp]
      (ui/send-key! s :return)
      (ui/send-key! s :return)
      (is (= "[n=2]" (str/trim (ui/render-text s 6 1)))))))

(deftest removed-subtrees-are-unmounted
  (let [show (atom true)
        comp (fn [] (let [n (atom 0)]
                      (fn [] (ascii-button (str "n=" @n) #(swap! n inc)))))
        app  (fn [] [:vbox (when @show [comp]) [:text "x"]])]
    (with-screen [s app]
      (ui/send-key! s :return)
      (is (= ["[n=1]" "x"] (lines (ui/render-text s 6 2))))
      (is (= 1 (:components (ui/stats s))))
      (reset! show false)
      (is (= ["x" ""] (lines (ui/render-text s 6 2))))
      (is (= 0 (:components (ui/stats s))) "the button was freed")
      (reset! show true)
      (is (= ["[n=0]" "x"] (lines (ui/render-text s 6 2))) "a fresh instance, fresh state"))))

(deftest containers-come-and-go-with-the-structure
  (let [n   (atom 3)
        app (fn [] (into [:hbox] (for [i (range @n)] (ascii-button (str i) (fn [])))))]
    (with-screen [s app]
      (ui/render-text s 12 1)
      (is (= {:components 3 :containers 1} (select-keys (ui/stats s) [:components :containers])))
      (reset! n 1)
      (ui/render-text s 12 1)
      (is (= {:components 1 :containers 0} (select-keys (ui/stats s) [:components :containers]))
          "a single focusable child needs no container"))))

(deftest keyed-children-keep-identity-across-reorder
  (let [order (atom [:a :b])
        vals  (atom {})
        row   (fn [k] [:input {:key k :placeholder (name k) :on-change #(swap! vals assoc k %)}])
        app   (fn [] (into [:vbox] (map row @order)))]
    (with-screen [s app]
      (ui/send-key! s :arrow-down)
      (ui/send-char! s "x")
      (is (= {:b "x"} @vals))
      (is (= ["a" "x"] (mapv str/trim (lines (ui/render-text s 6 2)))))
      (reset! order [:b :a])
      (is (= ["x" "a"] (mapv str/trim (lines (ui/render-text s 6 2))))
          "the input keyed :b moved up and kept its text"))))

(deftest root-on-event-sees-events-first
  (let [seen   (atom [])
        clicks (atom 0)
        app    (fn [] (ascii-button "b" #(swap! clicks inc)))]
    (with-screen [s app {:on-event (fn [e] (swap! seen conj e) (= :return (:key e)))}]
      (ui/send-char! s "q")
      (ui/send-key! s :return)
      (is (= 0 @clicks) "the root handler consumed Return")
      (is (= [{:type :character :char "q" :input "q"}
              {:type :key :key :return :input "\n"}]
             @seen)))))

(deftest catch-event-wraps-a-subtree
  (let [seen (atom [])
        app  (fn [] [:vbox [:catch-event {:on-event (fn [e] (swap! seen conj (:char e)) false)}
                            (ascii-button "b" (fn []))]])]
    (with-screen [s app]
      (ui/send-char! s "z")
      (is (= ["z"] @seen)))))

(deftest modal-routes-events-to-the-modal-when-shown
  (let [show (atom false)
        hits (atom [])
        app  (fn [] [:modal {:show @show}
                     (ascii-button "main" #(swap! hits conj :main))
                     (ascii-button "ok" #(do (swap! hits conj :ok) (reset! show false)))])]
    (with-screen [s app]
      (ui/send-key! s :return)
      (is (= [:main] @hits))
      (reset! show true)
      (ui/refresh! s)
      (is (str/includes? (ui/render-text s 12 3) "[ok]"))
      (ui/send-key! s :return)
      (is (= [:main :ok] @hits))
      (ui/send-key! s :return)
      (is (= [:main :ok :main] @hits) "closed by its own handler, events go back to main"))))

(deftest collapsible-reports-its-toggle
  (let [open (atom false)
        app  (fn [] [:collapsible {:label "more" :show @open :on-change #(reset! open %)}
                     [:text "hidden"]])]
    (with-screen [s app]
      (is (not (str/includes? (ui/render-text s 10 2) "hidden")))
      (ui/send-key! s :return)
      (is (true? @open))
      (is (str/includes? (ui/render-text s 10 2) "hidden")))))

(deftest errors-propagate-out-of-callbacks
  (testing "a throwing handler"
    (let [app (fn [] (ascii-button "b" #(throw (ex-info "boom" {}))))]
      (with-screen [s app]
        (is (thrown-with-msg? Exception #"boom" (ui/send-key! s :return))))))
  (testing "a throwing render"
    (let [bad (atom false)
          app (fn [] (if @bad (throw (ex-info "bad render" {})) [:text "ok"]))]
      (with-screen [s app]
        (is (= "ok" (ui/render-text s 2 1)))
        (reset! bad true)
        (is (thrown-with-msg? Exception #"bad render" (ui/render-text s 2 1)))))))

(deftest refresh-and-reactive-atom-without-an-app
  (is (nil? (ui/refresh!)) "no app running: a no-op")
  (let [a (ui/atom 1)]
    (swap! a inc)
    (is (= 2 @a))))


(deftest resizable-split-drags-its-separator
  (let [size (atom 4)
        seen (atom [])
        app  (fn [] [:resizable-split {:direction :left :size @size
                                       :on-change #(do (swap! seen conj %) (reset! size %))}
                     [:text "L"] [:text "R"]])]
    (with-screen [s app]
      (is (= "L   │R" (str/trimr (ui/render-text s 10 1))) "main takes :size columns, then the separator")
      (testing "pressing the separator and moving resizes the main pane"
        (ui/send-mouse! s {:x 4 :y 0 :motion :pressed})
        (ui/send-mouse! s {:x 6 :y 0 :motion :moved})
        (is (= [6] @seen))
        (is (= "L     │R" (str/trimr (ui/render-text s 10 1)))))
      (testing "the size is controlled: what the prop says is what is drawn"
        (reset! size 2)
        (is (= "L │R" (str/trimr (ui/render-text s 10 1))))))))

(deftest resizable-split-obeys-min-and-max
  (let [size (atom 4)
        app  (fn [] [:resizable-split {:size @size :min 2 :max 5 :on-change #(reset! size %)}
                     [:text "L"] [:text "R"]])]
    (with-screen [s app]
      (ui/render-text s 10 1)
      (ui/send-mouse! s {:x 4 :y 0 :motion :pressed})
      (ui/send-mouse! s {:x 9 :y 0 :motion :moved})
      (is (= 5 @size))
      (ui/send-mouse! s {:x 0 :y 0 :motion :moved})
      (is (= 2 @size)))))

(deftest split-direction-picks-the-side-main-takes
  (is (= ["L │R"] (lines (ui/render-text [:resizable-split {:direction :left :size 2}
                                          [:text "L"] [:text "R"]] 4 1))))
  (is (= ["B│M"] (lines (ui/render-text [:resizable-split {:direction :right :size 2}
                                         [:text "M"] [:text "B"]] 4 1)))
      "the back pane flexes, so main keeps its two columns on the right")
  (is (= ["T" "───" "B"] (lines (ui/render-text [:resizable-split {:direction :up :size 1}
                                                 [:text "T"] [:text "B"]] 3 3))))
  (is (= ["B" "───" "T"] (lines (ui/render-text [:resizable-split {:direction :bottom :size 1}
                                                 [:text "T"] [:text "B"]] 3 3))))
  (testing "without a :size prop the split keeps FTXUI's own default"
    (is (= "L                   │R" (str/trimr (ui/render-text [:resizable-split {} [:text "L"] [:text "R"]] 24 1))))))

(deftest hoverable-reports-the-mouse
  (let [seen (atom [])
        app  (fn [] [:hoverable {:on-change #(swap! seen conj %)} [:text "abcd"]])]
    (with-screen [s app]
      (ui/render-text s 6 1)                       ; a box is only known once drawn
      (ui/send-mouse! s {:x 1 :y 0 :motion :moved})
      (is (= [true] @seen))
      (ui/send-mouse! s {:x 5 :y 3 :motion :moved})
      (is (= [true false] @seen) "and once more when it leaves")
      (ui/send-mouse! s {:x 4 :y 3 :motion :moved})
      (is (= [true false] @seen) "staying outside says nothing"))))

(deftest floating-window-drags
  (let [geom (atom {:left 0 :top 0 :width 10 :height 4})
        app  (fn [] [:floating-window (assoc @geom :title "w" :on-change #(reset! geom %))
                     [:text "in"]])]
    (with-screen [s app]
      (is (= ["╭w───────╮" "│in      │" "│        │" "╰────────╯"] (lines (ui/render-text s 12 4))))
      (testing "pressing inside and moving drags the whole window"
        (ui/send-mouse! s {:x 3 :y 1 :motion :pressed})
        (ui/send-mouse! s {:x 5 :y 2 :motion :moved})
        (is (= {:left 2 :top 1 :width 10 :height 4} @geom))
        (is (= ["" "  ╭w───────╮" "  │in      │" "  │        │" "  ╰────────╯" ""]
               (lines (ui/render-text s 14 6))))))))

(deftest floating-window-resizes-from-its-border
  (let [geom (atom {:left 0 :top 0 :width 10 :height 4})
        app  (fn [] [:floating-window (assoc @geom :title "w" :on-change #(reset! geom %))
                     [:text "in"]])]
    (with-screen [s app]
      (ui/render-text s 20 6)
      (testing "the right edge resizes rather than drags"
        (ui/send-mouse! s {:x 9 :y 2 :motion :pressed})
        (ui/send-mouse! s {:x 12 :y 2 :motion :moved})
        (is (= {:left 0 :top 0 :width 13 :height 4} @geom)))))
  (testing ":resize false leaves the edges alone, so the same press drags"
    (let [geom (atom {:left 0 :top 0 :width 10 :height 4})
          app  (fn [] [:floating-window (assoc @geom :title "w" :resize false
                                               :on-change #(reset! geom %))
                       [:text "in"]])]
      (with-screen [s app]
        (ui/render-text s 20 6)
        (ui/send-mouse! s {:x 9 :y 2 :motion :pressed})
        (ui/send-mouse! s {:x 12 :y 2 :motion :moved})
        (is (= {:left 3 :top 0 :width 10 :height 4} @geom))))))

(deftest stacked-windows-take-the-mouse-in-drawing-order
  (let [ws  (atom {:a {:left 0 :top 0 :width 6 :height 3}
                   :b {:left 3 :top 1 :width 6 :height 3}})
        app (fn [] [:stack
                    [:floating-window (assoc (:a @ws) :title "a" :on-change #(swap! ws assoc :a %)) [:text "1"]]
                    [:floating-window (assoc (:b @ws) :title "b" :on-change #(swap! ws assoc :b %)) [:text "2"]]])]
    (with-screen [s app]
      (is (= ["╭a───╮" "│1 ╭b───╮" "╰──┤2   │" "   ╰────╯"] (lines (ui/render-text s 12 4))))
      (is (= 1 (:containers (ui/stats s))) "one stacked container holds them")
      (testing "a press where they overlap reaches the one drawn on top"
        (ui/send-mouse! s {:x 4 :y 2 :motion :pressed})
        (ui/send-mouse! s {:x 5 :y 3 :motion :moved})
        (is (= {:a {:left 0 :top 0 :width 6 :height 3}
                :b {:left 4 :top 2 :width 6 :height 3}} @ws))))))

(deftest universal-props-decorate-components
  (let [app (fn [] [:button {:label "b" :style :ascii :border true}])]
    (with-screen [s app]
      (is (= ["╭───╮" "│[b]│" "╰───╯"] (lines (ui/render-text s 5 3)))))))
