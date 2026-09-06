(ns ftxui.examples.smoke
  "Non-interactive check against a real terminal: run the counter under the
  FTXUI loop, press Enter on it from another thread (which clicks the
  focused +1 button), let the loop exit on a timer, and verify the click
  landed. Exit 0 only if the whole pipeline — shim load, app loop, render
  and event callbacks, clean shutdown — ran.

  Needs a terminal (FTXUI takes over stdin/stdout). Run with `jolt smoke`."
  (:require [ftxui.core :as ui :refer [atom]]))

(def clicks (atom 0))
(def renders (clojure.core/atom 0))

(defn app []
  (swap! renders inc)
  [:vbox {:border :rounded}
   [:text "clicks: " @clicks]
   [:button {:label "+1" :on-click #(swap! clicks inc)}]])

(defn- exit [code]
  (when-let [f (or (resolve 'jolt.host/exit) (resolve 'System/exit))]
    (f code)))

(defn -main [& _]
  (try
    (future
      (Thread/sleep 300)
      (ui/post-key! :return))
    (ui/run app :mode :fit-component :auto-exit-ms 900)
    (println)
    (prn :smoke :clicks @clicks :renders @renders)
    (if (and (= 1 @clicks) (>= @renders 2))
      (println "SMOKE OK")
      (do (println "SMOKE FAIL") (exit 1)))
    (catch Throwable e
      (println "SMOKE FAIL:" (ex-message e))
      (exit 1))))
