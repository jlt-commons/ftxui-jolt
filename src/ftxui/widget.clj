(ns ftxui.widget
  "The interactive components: hiccup tags for FTXUI's focusable widgets, and
  how their props reach the widget each frame.

  A widget lives across frames (it holds focus, a cursor, a selection), so it
  is created once at its position in the tree and updated after that. Each
  spec says how:

    :ctor       (fn [id props subtrees]) creates the FTXUI component under id
    :ctor-keys  props that need a new component when they change
    :apply      (fn [id props prev]) pushes props into the live component;
                prev is the previous frame's props, nil on the first
    :events     :on-* prop -> {:kind action-code :arg (fn [id] value)}
    :subtrees   :one / :two / :self — children are hiccup subtrees rendered
                through nodes the wrapper holds (see ftxui.render)
    :label-from-children  a string body doubles as :label
    :consumes   props the widget interprets, kept away from the decorators

  Controlled props (:value, :checked, :selected, :show) follow the reagent
  contract: whatever the prop says each frame is what the widget shows. The
  widget fires :on-change with the value the user produced; if the handler
  does not write it back, the next frame restores the prop's value."
  (:require [ftxui.ffi :as f]
            [ftxui.color :as color]
            [ftxui.dom :as dom]))

(def button-styles {:simple 0 :ascii 1 :border 2 :animated 3})
(def menu-styles {:plain 0 :animated 1 :toggle 2})

(defn- lookup [table x what]
  (or (get table x)
      (throw (ex-info (str "widget: unknown " what " " (pr-str x)) {:value x :known (keys table)}))))

(defn- changed? [props prev k]
  (or (nil? prev) (not= (get props k) (get prev k))))

(defn- push-label! [id props prev]
  (when (changed? props prev :label) (f/set-label id (str (:label props "")))))

(defn- push-entries! [id props prev]
  (when (changed? props prev :entries)
    (f/entries-clear id)
    (doseq [e (:entries props)] (f/entries-add id (str e)))))

;; Controlled props compare against the widget's *current* state, not the
;; previous prop, so a user edit the handler rejected is reverted.
(defn- push-selected! [id props]
  (when (contains? props :selected)
    (let [v (int (or (:selected props) 0))]
      (when (not= v (f/get-selected id)) (f/set-selected id v)))))

(defn- push-checked! [id props]
  (when (contains? props :checked)
    (let [b (boolean (:checked props))]
      (when (not= b (= 1 (f/get-checked id))) (f/set-checked id (if b 1 0))))))

(defn- push-show! [id props]
  (when (contains? props :show)
    (let [b (boolean (:show props))]
      (when (not= b (= 1 (f/get-show id))) (f/set-show id (if b 1 0))))))

(defn- ->bool [x] (if x 1 0))

(def specs
  {:button
   {:ctor (fn [id props _] (f/button-new id (lookup button-styles (:style props :simple) "button style")))
    :ctor-keys [:style]
    :label-from-children true
    :apply (fn [id props prev] (push-label! id props prev))
    :events {:on-click {:kind 0}}
    :consumes [:label :style :on-click]}

   :input
   {:ctor (fn [id _ _] (f/input-new id))
    :apply (fn [id props prev]
             (when (contains? props :value)
               (let [v (str (:value props))]
                 (when (not= v (f/get-content id)) (f/set-content id v))))
             (when (changed? props prev :placeholder) (f/set-placeholder id (str (:placeholder props ""))))
             (when (changed? props prev :password) (f/set-password id (->bool (:password props))))
             (when (changed? props prev :multiline) (f/set-multiline id (->bool (:multiline props)))))
    :events {:on-change {:kind 1 :arg f/get-content}
             :on-enter  {:kind 2 :arg f/get-content}}
    :consumes [:value :placeholder :password :multiline :on-change :on-enter]}

   :checkbox
   {:ctor (fn [id _ _] (f/checkbox-new id))
    :label-from-children true
    :apply (fn [id props prev] (push-label! id props prev) (push-checked! id props))
    :events {:on-change {:kind 1 :arg #(= 1 (f/get-checked %))}}
    :consumes [:label :checked :on-change]}

   :menu
   {:ctor (fn [id props _]
            (f/menu-new id (lookup dom/directions (:direction props :down) "direction")
                        (lookup menu-styles (:style props :plain) "menu style")))
    :ctor-keys [:direction :style]
    :apply (fn [id props prev] (push-entries! id props prev) (push-selected! id props))
    :events {:on-change {:kind 1 :arg f/get-selected}
             :on-enter  {:kind 2 :arg f/get-selected}}
    :consumes [:entries :selected :direction :style :on-change :on-enter]}

   :toggle
   {:ctor (fn [id _ _] (f/menu-new id 3 2))
    :apply (fn [id props prev] (push-entries! id props prev) (push-selected! id props))
    :events {:on-change {:kind 1 :arg f/get-selected}
             :on-enter  {:kind 2 :arg f/get-selected}}
    :consumes [:entries :selected :on-change :on-enter]}

   :radiobox
   {:ctor (fn [id _ _] (f/radiobox-new id))
    :apply (fn [id props prev] (push-entries! id props prev) (push-selected! id props))
    :events {:on-change {:kind 1 :arg f/get-selected}}
    :consumes [:entries :selected :on-change]}

   :dropdown
   {:ctor (fn [id _ _] (f/dropdown-new id))
    :apply (fn [id props prev]
             (push-entries! id props prev)
             (push-selected! id props)
             (when (contains? props :open)
               (let [b (boolean (:open props))]
                 (when (not= b (= 1 (f/get-show id))) (f/set-show id (->bool b))))))
    :events {:on-change {:kind 1 :arg f/get-selected}}
    :consumes [:entries :selected :open :on-change]}

   :slider
   {:ctor (fn [id props _]
            (f/slider-new id (lookup dom/directions (:direction props :right) "direction")
                          (color/code (:color props)) (color/code (:color-inactive props))))
    :ctor-keys [:direction :color :color-inactive]
    :apply (fn [id props prev]
             (when (or (changed? props prev :min) (changed? props prev :max) (changed? props prev :increment))
               (f/set-range id (int (:min props 0)) (int (:max props 100)) (int (:increment props 1))))
             (when (contains? props :value)
               (let [v (int (or (:value props) 0))]
                 (when (not= v (f/get-value id)) (f/set-value id v)))))
    :events {:on-change {:kind 1 :arg f/get-value}}
    :consumes [:value :min :max :increment :direction :color :color-inactive :on-change]}

   ;; wrappers over hiccup subtrees
   :maybe
   {:subtrees :one
    :ctor (fn [id _ [child]] (f/maybe-new id child))
    :apply (fn [id props _] (push-show! id (if (contains? props :show) props (assoc props :show true))))
    :consumes [:show]}

   :modal
   {:subtrees :two
    :ctor (fn [id _ [main modal]] (f/modal-new id main modal))
    :apply (fn [id props _] (push-show! id props))
    :consumes [:show]}

   :collapsible
   {:subtrees :one
    :label-from-children false
    :ctor (fn [id _ [child]] (f/collapsible-new id child))
    :apply (fn [id props prev] (push-label! id props prev) (push-show! id props))
    :events {:on-change {:kind 1 :arg #(= 1 (f/get-show %))}}
    :consumes [:label :show :on-change]}

   ;; the component is itself a node: its children are its content, and its
   ;; :on-event sees every event before they do
   :catch-event
   {:subtrees :self
    :consumes [:on-event]}})

(defn spec
  "The widget spec for `tag`, or nil when it is not a widget tag."
  [tag]
  (get specs tag))

(defn widget-tag? [tag] (contains? specs tag))
