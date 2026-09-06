(ns ftxui.examples.counter
  "The canonical reagent-style demo: a Form-2 component with a local atom,
  buttons that swap it, a label that reads it. FTXUI redraws after every
  click, so nothing subscribes to anything.

  Run with `jolt counter`. Tab/arrows move between the buttons, Enter (or a
  click) presses one, Ctrl-C or the quit button exits."
  (:require [ftxui.core :as ui :refer [atom]]))

(defn counter
  "Form-2: the outer fn runs once and creates the state; the inner fn renders."
  []
  (let [n (atom 0)]
    (fn []
      [:vbox {:border :rounded}
       [:text {:bold true} " Count: " @n " "]
       [:separator]
       [:hbox
        [:button {:label "-1" :on-click #(swap! n dec)}]
        [:button {:label "+1" :on-click #(swap! n inc)}]
        [:button {:label "reset" :on-click #(reset! n 0)}]
        [:button {:label "quit" :on-click ui/exit!}]]])))

(defn -main [& _]
  (ui/run counter :mode :fit-component))
