(ns ftxui.examples.todo
  "A task board: an input that adds on Enter, a keyed list of checkboxes,
  derived counts, and universal props (:dim, :strikethrough, :flex, :frame)
  on both elements and widgets.

  Global state lives in ftxui.core/atom cells, so a change from anywhere
  (a REPL under `jolt nrepl-server`, say) redraws; changes made by the
  handlers here would redraw anyway, because every event ends in a draw.

  Run with `jolt todo`. Up/Down move through the tasks, Space or Enter
  toggles one, Tab reaches the input; Ctrl-C exits."
  (:require [clojure.string :as str]
            [ftxui.core :as ui :refer [atom]]))

(def tasks
  (atom [{:id 1 :text "Try the counter demo" :done true}
         {:id 2 :text "Toggle a task with Space or Enter" :done false}
         {:id 3 :text "Type below and press Enter to add one" :done false}]))

(def draft (atom ""))
(def next-id (clojure.core/atom 3))

(defn add-task! [text]
  (when-not (str/blank? text)
    (swap! tasks conj {:id (swap! next-id inc) :text (str/trim text) :done false})
    (reset! draft "")))

(defn- set-done [ts id done?]
  (mapv #(if (= id (:id %)) (assoc % :done done?) %) ts))

(defn task-row
  "One task: a checkbox whose :checked is the task's :done. The row is keyed
  by task id (see app) so it keeps its identity when the list changes."
  [{:keys [id text done]}]
  [:checkbox {:label text :checked done
              :dim done :strikethrough done
              :on-change #(swap! tasks set-done id %)}])

(defn- stat [n label]
  [:vbox {:width 9}
   [:text {:bold true :align :center} n]
   [:text {:dim true :align :center} label]])

(defn app []
  (let [ts   @tasks
        done (count (filter :done ts))]
    [:vbox {:border :rounded}
     [:text {:bold true} " Tasks"]
     [:hbox
      (stat (count ts) "total") [:separator]
      (stat done "done") [:separator]
      (stat (- (count ts) done) "left")]
     [:separator]
     [:vbox {:flex true :frame true}
      (if (empty? ts)
        [:text {:dim true} "Nothing here yet — add a task below."]
        (for [t ts] ^{:key (:id t)} [task-row t]))]
     [:separator]
     [:hbox
      [:input {:value @draft :placeholder "Add a task…" :flex true
               :on-change #(reset! draft %)
               :on-enter add-task!}]
      [:button {:label "Add" :on-click #(add-task! @draft)}]]]))

(defn -main [& _]
  (ui/run app))
