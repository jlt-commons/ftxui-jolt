(ns ftxui.core
  "A reagent-style API over FTXUI, the C++ terminal UI library.

    (ns myapp
      (:require [ftxui.core :as ui :refer [atom]]))

    (def count (atom 0))

    (defn app []
      [:vbox {:border :rounded}
       [:text \"Count: \" @count]
       [:hbox
        [:button {:label \"-1\" :on-click #(swap! count dec)}]
        [:button {:label \"+1\" :on-click #(swap! count inc)}]
        [:button {:label \"quit\" :on-click ui/exit!}]]])

    (defn -main [& _] (ui/run app))

  Components are functions returning hiccup, re-run on every frame (Form-1),
  or returning a render fn that is (Form-2, for local state). FTXUI redraws
  after every event, so state changed by a handler shows up on the next
  frame with no subscription machinery; state changed from anywhere else — a
  timer, another thread, the REPL — reaches the screen through refresh!, or
  automatically when it lives in an `atom` from this namespace.

  Everything except `run` also works headlessly: mount a component, send it
  keys and read the frame back as text. That is how the test suite drives
  real FTXUI widgets without a terminal."
  (:refer-clojure :exclude [atom])
  (:require [ftxui.ffi :as f]
            [ftxui.keys :as keys]
            [ftxui.render :as r]))

;; --- the app loop ---------------------------------------------------------------
(def modes
  "run's :mode values -> the shim's screen modes."
  {:fullscreen 0 :fit-component 1 :terminal-output 2 :fixed 3
   :fullscreen-alternate 4 :fullscreen-primary 5})

(defn exit!
  "Stop the running app loop (from any thread). A no-op when none runs."
  []
  (when-let [app @r/active-app] (f/app-exit app))
  nil)

(defn refresh!
  "Ask for a redraw.

  With no argument: post a custom event to the running app (thread-safe),
  so a state change made outside an event handler is drawn. Skipped when
  called from inside a handler or a render — a redraw follows those anyway
  — and a no-op when no app is running.

  With a headless screen from `mount`: prepare its next frame now, as the
  app loop would before the next draw."
  ([]
   (when-let [app @r/active-app]
     (when-not (r/in-callback?) (f/app-post-custom app)))
   nil)
  ([screen]
   (r/prepare-frame! screen)
   (r/check-error! screen)
   nil))

(defn post-key!
  "Queue a named key event on the running app, from any thread — for
  automation and smoke tests. A no-op when no app is running."
  [k]
  (when-let [app @r/active-app] (f/app-post-key app (keys/code k)))
  nil)

(defn post-char!
  "Queue typed text on the running app, one character event per code point."
  [s]
  (when-let [app @r/active-app]
    (doseq [c (seq (str s))]
      (f/app-post-char app (str c))))
  nil)

(defn atom
  "A clojure atom that calls refresh! whenever its value changes, so writing
  it from a timer, a future or the REPL redraws the running app."
  [x & opts]
  (let [a (apply clojure.core/atom x opts)]
    (add-watch a ::refresh (fn [_ _ old new] (when (not= old new) (refresh!))))
    a))

(defn reload!
  "Remount the running app's components — after redefining them at the
  REPL — and redraw. A no-op when no app is running."
  []
  (when-let [m @r/current-mount]
    (r/remount! m)
    (refresh!))
  nil)

(defn run
  "Mount `component` (a component fn, or hiccup) and run the FTXUI loop on
  the calling thread until exit! (or Ctrl-C). Options:

    :mode          :fullscreen (default), :fit-component, :terminal-output,
                   :fixed (with :width/:height), :fullscreen-alternate,
                   :fullscreen-primary
    :width :height the size for :fixed
    :mouse         false to leave mouse tracking off
    :on-event      (fn [event]) that sees every event first; return truthy
                   to consume it
    :auto-exit-ms  exit after this many milliseconds (smoke tests)
    :async         true to run the loop on another thread and return a
                   future right away — for a REPL session (jolt nrepl-server)
                   that wants to keep evaluating while the UI runs

  An exception thrown by a handler or a render stops the loop and is
  rethrown here."
  [component & {:keys [mode width height mouse on-event auto-exit-ms async] :as _opts}]
  (let [m (r/mount component {:on-event on-event})
        mode (or mode :fullscreen)
        app (f/app-new (or (get modes mode)
                           (throw (ex-info (str "run: unknown :mode " mode) {:mode mode :known (keys modes)})))
                       (int (or width 0)) (int (or height 0)))
        go (fn []
             (try
               (reset! r/active-app app)
               (reset! r/current-mount m)
               (when (false? mouse) (f/app-track-mouse app 0))
               (when auto-exit-ms
                 (future (Thread/sleep auto-exit-ms) (f/app-exit app)))
               (f/app-loop app (:root-id m))
               (finally
                 (reset! r/active-app nil)
                 (reset! r/current-mount nil)
                 (r/unmount! m)
                 (f/app-free app)))
             (r/check-error! m)
             nil)]
    (if async (future (go)) (go))))

;; --- headless -------------------------------------------------------------------
(defn mount
  "Mount `component` without a terminal and return a screen to drive with
  send-key!, send-char!, send-mouse!, refresh! and render-text. Release it
  with unmount!. `opts` as for run's :on-event."
  ([component] (mount component {}))
  ([component opts] (r/mount component opts)))

(defn unmount!
  "Free a headless screen's components."
  [screen]
  (r/unmount! screen))

(defmacro with-screen
  "(with-screen [s component opts?] body...) — mount, run body, unmount."
  [[sym component & [opts]] & body]
  `(let [~sym (mount ~component ~(or opts {}))]
     (try ~@body (finally (unmount! ~sym)))))

(defn- screen? [x] (and (map? x) (contains? x :root-id)))

(defn- with-temp-screen [component f]
  (let [s (mount component)]
    (try (f s) (finally (unmount! s)))))

(defn render-text
  "Render the next frame of a headless screen — or of a bare component /
  hiccup — onto a w x h screen and return it as text: rows joined by
  newlines, no escape codes."
  [target w h]
  (if (screen? target)
    (let [out (f/component-render-text (:root-id target) (int w) (int h))]
      (r/check-error! target)
      out)
    (with-temp-screen target #(render-text % w h))))

(defn render-ansi
  "Like render-text, but as the terminal would receive it: styles and colors
  as escape sequences."
  [target w h]
  (if (screen? target)
    (let [out (r/render-ansi target (int w) (int h))]
      (r/check-error! target)
      out)
    (with-temp-screen target #(render-ansi % w h))))

(defn- after-event! [screen]
  (r/check-error! screen)
  (r/prepare-frame! screen)
  (r/check-error! screen))

(defn send-key!
  "Deliver a named key (:return, :tab, :arrow-down, :ctrl-c, :f1 ...) to a
  headless screen, then prepare the next frame as the loop would. Returns
  true when the tree handled it."
  [screen k]
  (let [handled (= 1 (f/send-key (:root-id screen) (keys/code k)))]
    (after-event! screen)
    handled))

(defn send-char!
  "Deliver typed text, one character event per code point."
  [screen s]
  (let [handled (reduce (fn [acc c] (or (= 1 (f/send-char (:root-id screen) (str c))) acc))
                        false (seq (str s)))]
    (after-event! screen)
    handled))

(defn send-mouse!
  "Deliver a mouse event: {:button :left :motion :pressed :x 3 :y 1}."
  [screen {:keys [button motion x y] :or {button :left motion :pressed x 0 y 0}}]
  (let [handled (= 1 (f/send-mouse (:root-id screen) (keys/button-code button)
                                   (keys/motion-code motion) (int x) (int y)))]
    (after-event! screen)
    handled))

(defn stats
  "Live component counts for a headless screen (or the running app):
  {:components :containers :nodes :fns}."
  ([] (some-> @r/current-mount r/stats))
  ([screen] (r/stats screen)))
