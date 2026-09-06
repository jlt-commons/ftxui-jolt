(ns ftxui.render
  "The frame: hiccup in, an FTXUI component tree kept in step, a DOM out.

  FTXUI renders by calling Render() on a component tree on every event, and
  Render() rebuilds the DOM from scratch. That is already reagent's model —
  a render function re-run whenever something changes — so the job here is
  smaller than a reconciler's:

  1. prepare: run the root component to get hiccup and walk it. Elements
     become plain nodes to build later. Widgets (:button, :input, ...) are
     looked up by their position in the tree — path of indexes or :key
     values — and created once, then updated; Form-2 component fns are
     cached the same way so their local state persists. Layouts with more
     than one focusable descendant get an FTXUI container, so the layout
     tree doubles as the focus tree: arrows and Tab move through the UI the
     way it is laid out.
  2. sweep: anything not visited this frame is unmounted.
  3. build: the prepared tree becomes element handles (ftxui.dom).

  The root is a node — a component whose Render() calls back into jolt —
  and so is every subtree a wrapper widget (modal, collapsible) holds. The
  render callback runs steps 1-3 for the root, or just 3 for a subtree
  prepared during the root's pass."
  (:require [ftxui.ffi :as f]
            [ftxui.dom :as dom]
            [ftxui.widget :as w]
            [ftxui.keys :as keys]
            [jolt.ffi :as ffi]))

;; --- registries ---------------------------------------------------------------
(def ^:private ids (atom 0))
(defn- next-id! [] (swap! ids inc))

;; component id -> mount, for every component we create (widgets, nodes,
;; containers). The C callbacks only carry an id.
(def ^:private owners (atom {}))
;; component id -> {:on-click {:fn f :arg argfn} ... :on-event f}
(def ^:private handlers (atom {}))
;; The app whose loop is running, if any (set by ftxui.core/run).
(def active-app (atom nil))
;; The mount the running app renders.
(def current-mount (atom nil))
;; > 0 while inside a callback from FTXUI (render, action, event).
(def ^:private callback-depth (atom 0))

(defn in-callback? [] (pos? @callback-depth))

;; --- errors -------------------------------------------------------------------
;; A jolt exception cannot unwind through FTXUI's C++ frames, so callbacks
;; catch everything, record it on the mount and stop the loop; run / the
;; headless entry points rethrow it once control is back in jolt.
(defn- record-error! [id e]
  (when-let [m (@owners id)]
    (reset! (:error m) e))
  (when-let [app @active-app]
    (f/app-exit app)))

(defn check-error!
  "Rethrow (and clear) an error recorded on mount `m` during a callback."
  [m]
  (when-let [e @(:error m)]
    (reset! (:error m) nil)
    (throw e)))

(defmacro ^:private guarded
  "Run body inside a callback: track depth, route any throw to record-error!."
  [id & body]
  `(do (swap! callback-depth inc)
       (try ~@body
            (catch Throwable e# (record-error! ~id e#) nil)
            (finally (swap! callback-depth dec)))))

;; --- cache helpers ------------------------------------------------------------
;; A mount's cache maps a tree path to an entry:
;;   {:type :fn        :f f :render r}          a component fn (r = Form-2 render fn)
;;   {:type :widget    :id n :tag k :ctor-props m :subs [ids] :props m}
;;   {:type :node      :id n :handler? bool}    a subtree rendered through jolt
;;   {:type :container :id n :kind :vertical|:horizontal}

(defn- cache-get [ctx path] (get @(:cache (:mount ctx)) path))
(defn- cache-put! [ctx path entry] (swap! (:cache (:mount ctx)) assoc path entry) entry)
(defn- visit! [ctx path] (swap! (:visited ctx) conj path))

(defn- dispose!
  "Free whatever `entry` (at `path`) holds on the C side and forget it."
  [m path entry]
  (when entry
    (when-let [id (:id entry)]
      (f/component-free id)
      (swap! owners dissoc id)
      (swap! handlers dissoc id)
      (swap! (:subtrees m) dissoc id))
    (swap! (:cache m) dissoc path)))

(defn- create-component!
  "Register a freshly created component id with its mount."
  [ctx id]
  (swap! owners assoc id (:mount ctx))
  id)

;; --- hiccup helpers -----------------------------------------------------------
(defn- splice
  "Flatten seqs (not vectors) into the child list. nils are kept so that
  positions stay stable when a (when ...) child comes and goes."
  [children]
  (reduce (fn step [acc x]
            (if (and (sequential? x) (not (vector? x)))
              (reduce step acc x)
              (conj acc x)))
          [] children))

(defn- child-key [x]
  (when (vector? x)
    (let [p (second x)]
      (or (when (map? p) (:key p))
          (:key (meta x))))))

(defn- split-tag
  "[tag props? & children] -> [tag props children]"
  [form]
  (let [[tag & body] form
        props? (map? (first body))]
    [tag (if props? (first body) {}) (if props? (rest body) body)]))

(defn- text-of [children]
  (apply str (remove nil? (splice children))))

;; --- prepare ------------------------------------------------------------------
(declare prepare)

(defn- prepare-children
  "Prepare each child at path + (its :key or index). Returns a vector of
  {:node :focus} results (nil children dropped)."
  [ctx children path]
  (let [xs (splice children)]
    (into []
          (keep-indexed (fn [i x]
                          (when (some? x)
                            (prepare ctx x (conj path (or (child-key x) i))))))
          xs)))

(def ^:private container-codes {:vertical 0 :horizontal 1 :tab 2 :stacked 3})

(defn- focus-root!
  "Reduce a list of focusable ids to at most one: several get an FTXUI
  container of `kind` cached at path, whose children they become."
  [ctx path focus kind]
  (if (> (count focus) 1)
    (let [cpath (conj path ::container)
          entry (cache-get ctx cpath)
          entry (if (and entry (= :container (:type entry)) (= kind (:kind entry)))
                  entry
                  (do (dispose! (:mount ctx) cpath entry)
                      (let [id (create-component! ctx (next-id!))]
                        (f/container-new id (container-codes kind))
                        (cache-put! ctx cpath {:type :container :id id :kind kind}))))]
      (visit! ctx cpath)
      (f/set-children (:id entry) focus)
      [(:id entry)])
    (vec focus)))

(defn- prepare-fn
  "[component-fn & args]: a Form-1 fn returns hiccup; a Form-2 fn returns
  a render fn, cached so its closed-over state persists at this position."
  [ctx [fun & args :as form] path]
  (let [entry (cache-get ctx path)
        entry (if (and entry (= :fn (:type entry)) (identical? (:f entry) fun))
                entry
                (do (dispose! (:mount ctx) path entry)
                    (cache-put! ctx path {:type :fn :f fun :render nil})))
        render (:render entry)
        result (if render (apply render args) (apply fun args))
        [render result] (if (and (nil? render) (fn? result))
                          [result (apply result args)]
                          [render result])]
    (when (and render (not (:render entry)))
      (cache-put! ctx path (assoc entry :render render)))
    (visit! ctx path)
    (when (and (sequential? result) (not (vector? result)))
      (throw (ex-info "component returned a seq; wrap it in [:vbox ...] or [:hbox ...]"
                      {:component fun :form form})))
    (prepare ctx result (conj path :>))))

(defn- prepare-hiccup-props
  "Props that hold hiccup (a :window's :title) are prepared like children."
  [ctx info props path]
  (reduce (fn [acc k]
            (if (contains? props k)
              (assoc acc k (:node (prepare ctx (get props k) (conj path k))))
              acc))
          {}
          (:hiccup-props info)))

(defn- prepare-element [ctx tag props children path]
  (let [info (dom/tag-info tag)
        props (if-let [pf (:props info)] (pf props) props)
        base {:type :element :tag tag :props props}]
    (case (:kind info)
      :text {:node (assoc base :text (text-of children)) :focus []}
      :leaf {:node base :focus []}
      :rows (let [rows (map-indexed
                         (fn [r row] (prepare-children ctx row (conj path r)))
                         (:rows props))
                  rows (mapv vec rows)
                  focus (into [] (mapcat #(mapcat :focus %)) rows)]
              {:node (assoc base :rows (mapv #(mapv :node %) rows))
               :focus (focus-root! ctx path focus (:container info :vertical))})
      (:layout :wrap)
      (let [kids (prepare-children ctx children path)
            pp (prepare-hiccup-props ctx info props path)
            focus (into [] (mapcat :focus) kids)
            kind (or (:container info) :horizontal)]
        {:node (cond-> (assoc base :children (mapv :node kids))
                 (seq pp) (assoc :prepared-props pp))
         :focus (focus-root! ctx path focus kind)})
      (throw (ex-info (str "dom: tag " tag " has no kind") {:tag tag})))))

(defn- ensure-node!
  "A node component at `path`: created once, re-created if it gains or
  loses an :on-event handler (that is baked into the C side)."
  [ctx path handler?]
  (let [entry (cache-get ctx path)
        entry (if (and entry (= :node (:type entry)) (= handler? (:handler? entry)))
                entry
                (do (dispose! (:mount ctx) path entry)
                    (let [id (create-component! ctx (next-id!))]
                      (f/node-new id (if handler? 1 0))
                      (cache-put! ctx path {:type :node :id id :handler? handler?}))))]
    (visit! ctx path)
    entry))

(defn- prepare-subtree
  "Hiccup `children` rendered through a node of their own. Returns the node
  id; the prepared content is stored on the mount for the render callback,
  and the node's children are the content's focus roots."
  [ctx children path on-event]
  (let [entry (ensure-node! ctx path (some? on-event))
        id (:id entry)
        kids (prepare-children ctx children (conj path :>))
        focus (focus-root! ctx path (into [] (mapcat :focus) kids) :vertical)
        node (case (count kids)
               0 {:type :element :tag :empty :props {}}
               1 (:node (first kids))
               {:type :element :tag :vbox :props {} :children (mapv :node kids)})]
    (f/set-children id focus)
    (swap! (:subtrees (:mount ctx)) assoc id node)
    (if on-event
      (swap! handlers assoc id {:on-event on-event})
      (swap! handlers dissoc id))
    id))

(defn- ensure-widget! [ctx path tag props spec subs]
  (let [entry (cache-get ctx path)
        ctor-props (select-keys props (:ctor-keys spec))
        fresh? (or (nil? entry)
                   (not= :widget (:type entry))
                   (not= tag (:tag entry))
                   (not= ctor-props (:ctor-props entry))
                   (not= subs (:subs entry)))]
    (if fresh?
      (do (dispose! (:mount ctx) path entry)
          (let [id (create-component! ctx (next-id!))]
            ((:ctor spec) id props subs)
            (cache-put! ctx path {:type :widget :tag tag :id id :ctor-props ctor-props
                                  :subs subs :props nil})))
      entry)))

(defn- register-handlers! [id spec props]
  (let [hs (reduce-kv (fn [acc k {:keys [arg]}]
                        (if-let [h (get props k)] (assoc acc k {:fn h :arg arg}) acc))
                      {} (:events spec))]
    (if (seq hs) (swap! handlers assoc id hs) (swap! handlers dissoc id))))

(defn- split-two [children]
  (let [xs (remove nil? (splice children))]
    (when (not= 2 (count xs))
      (throw (ex-info "expected exactly two children (main and modal)" {:children children})))
    xs))

(defn- prepare-widget [ctx tag props children path]
  (let [spec (w/spec tag)
        props (if (and (:label-from-children spec) (not (contains? props :label)) (seq children))
                (assoc props :label (text-of children))
                props)]
    (if (= :self (:subtrees spec))
      (let [id (prepare-subtree ctx children path (:on-event props))]
        {:node {:type :component :id id :tag tag :props (apply dissoc props (:consumes spec))}
         :focus [id]})
      (let [subs (case (:subtrees spec)
                   :one [(prepare-subtree ctx children (conj path 0) nil)]
                   :two (let [[a b] (split-two children)]
                          [(prepare-subtree ctx [a] (conj path 0) nil)
                           (prepare-subtree ctx [b] (conj path 1) nil)])
                   nil)
            entry (ensure-widget! ctx path tag props spec subs)
            id (:id entry)
            prev (:props entry)]
        (when-let [apply-fn (:apply spec)] (apply-fn id props prev))
        (when (and (:autofocus props) (not (:autofocus prev)))
          (swap! (:autofocus ctx) conj id))
        (cache-put! ctx path (assoc entry :props props))
        (register-handlers! id spec props)
        (visit! ctx path)
        {:node {:type :component :id id :tag tag :props (apply dissoc props :autofocus :key (:consumes spec))}
         :focus [id]}))))

(defn- prepare-tagged [ctx form path]
  (let [[tag props children] (split-tag form)]
    (cond
      (w/spec tag)       (prepare-widget ctx tag props children path)
      (dom/tag-info tag) (prepare-element ctx tag props children path)
      :else (throw (ex-info (str "hiccup: unknown tag " tag) {:tag tag :form form})))))

(defn- prepare
  "Walk hiccup `form` at `path`. Returns {:node prepared :focus [ids]}."
  [ctx form path]
  (cond
    (nil? form) nil
    (or (string? form) (number? form) (keyword? form) (symbol? form) (char? form))
    {:node {:type :text :text (str form)} :focus []}
    (vector? form)
    (let [head (first form)]
      (cond
        (keyword? head) (prepare-tagged ctx form path)
        (fn? head)      (prepare-fn ctx form path)
        :else (throw (ex-info "hiccup: a vector must start with a tag or a component fn"
                              {:form form}))))
    (and (sequential? form) (not (vector? form)))
    (throw (ex-info "hiccup: a bare seq is not an element; wrap it in [:vbox ...] or [:hbox ...]"
                    {:form form}))
    :else (throw (ex-info (str "hiccup: unsupported form " (pr-str form)) {:form form}))))

;; --- the frame ----------------------------------------------------------------
(defn- sweep! [ctx]
  (let [m (:mount ctx)
        visited @(:visited ctx)]
    (doseq [[path entry] @(:cache m)
            :when (not (contains? visited path))]
      (dispose! m path entry))))

(defn prepare-frame!
  "Run the root component, walk its hiccup, keep the FTXUI component tree in
  step, and return the prepared root node (stored on the mount for the
  render callback as well)."
  [m]
  (let [ctx {:mount m :visited (atom #{}) :autofocus (atom [])}
        {:keys [node focus]} (prepare ctx (:hiccup m) [])
        root-id (:root-id m)
        focus (focus-root! ctx [] (or focus []) :vertical)]
    (f/set-children root-id focus)
    (sweep! ctx)
    (doseq [id @(:autofocus ctx)] (f/take-focus id))
    (swap! (:subtrees m) assoc root-id node)
    node))

;; --- callbacks from the C side --------------------------------------------------
(defn- on-render [id]
  (if-let [m (@owners id)]
    (or (guarded id
          (if (= id (:root-id m))
            (dom/build (prepare-frame! m))
            (dom/build (or (get @(:subtrees m) id) {:type :element :tag :empty :props {}}))))
        (f/text (str "render error: " (some-> @(:error m) ex-message))))
    0))

(def ^:private kind->event {0 :on-click 1 :on-change 2 :on-enter})

(defn- on-action [id kind]
  (when-let [{:keys [fn arg]} (get-in @handlers [id (kind->event kind)])]
    (guarded id (if arg (fn (arg id)) (fn)))))

(defn- on-event [id ptr]
  (if-let [h (get-in @handlers [id :on-event])]
    (if (guarded id (h (keys/decode ptr))) 1 0)
    0))

;; Created once; they go through the vars so a redefinition at the REPL takes
;; effect. :collect-safe: FTXUI calls them from inside a :blocking call.
(defonce ^:private callbacks
  (let [r (ffi/foreign-callable (fn [id] (int (or (on-render id) 0))) [:int] :int :collect-safe)
        a (ffi/foreign-callable (fn [id kind] (on-action id kind) nil) [:int :int] :void :collect-safe)
        e (ffi/foreign-callable (fn [id ptr] (int (on-event id ptr))) [:int :pointer] :int :collect-safe)]
    (f/set-callbacks r a e)
    [r a e]))

;; --- mounts ---------------------------------------------------------------------
(defn mount
  "Create the root node for `component` (a component fn, or hiccup) and
  prepare its first frame. `opts` may carry :on-event, a handler that sees
  every event before the tree does (return truthy to consume it)."
  [component opts]
  (let [root-id (next-id!)
        m {:root-id root-id
           :hiccup (if (fn? component) [component] component)
           :opts opts
           :cache (atom {})
           :subtrees (atom {})
           :error (atom nil)}]
    (f/node-new root-id (if (:on-event opts) 1 0))
    (swap! owners assoc root-id m)
    (when-let [h (:on-event opts)] (swap! handlers assoc root-id {:on-event h}))
    (try
      (prepare-frame! m)
      m
      (catch Throwable e
        (f/component-free root-id)
        (swap! owners dissoc root-id)
        (swap! handlers dissoc root-id)
        (throw e)))))

(defn unmount!
  "Free every component the mount created, then its root."
  [m]
  (doseq [[path entry] @(:cache m)] (dispose! m path entry))
  (f/component-free (:root-id m))
  (swap! owners dissoc (:root-id m))
  (swap! handlers dissoc (:root-id m))
  (reset! (:subtrees m) {})
  nil)

(defn remount!
  "Drop every cached component and component fn so the next frame mounts
  them afresh — what a REPL reload wants after redefining components."
  [m]
  (doseq [[path entry] @(:cache m)] (dispose! m path entry))
  (prepare-frame! m)
  nil)

(defn render-ansi
  "Render the mount's next frame on a w x h screen, with escape sequences."
  [m w h]
  (let [handle (f/component-render (:root-id m))]
    (f/render-ansi handle w h)))

(defn stats
  "How many live things the mount holds: {:components :containers :nodes :fns}."
  [m]
  (let [es (vals @(:cache m))
        n (fn [t] (count (filter #(= t (:type %)) es)))]
    {:components (n :widget) :containers (n :container) :nodes (n :node) :fns (n :fn)}))
