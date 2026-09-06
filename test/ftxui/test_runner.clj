(ns ftxui.test-runner
  "Entry point for `jolt -M:test` (and the :test task). Requires each test
  namespace and runs clojure.test over them; exits non-zero on any failure so
  CI fails. Everything here is headless — no terminal is needed."
  (:require [clojure.test :as t]
            [ftxui.ffi :as ffi]))

;; Surface full causes on :error — the default report swallows the throwable.
(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn- exit [code]
  (cond
    (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) code)
    (resolve 'System/exit)    ((resolve 'System/exit) code)
    :else nil))

(def namespaces
  '[ftxui.color-test
    ftxui.keys-test
    ftxui.dom-test
    ftxui.render-test])

(defn -main [& _]
  ;; FTXUI picks its color depth from TERM / COLORTERM on the first render,
  ;; so pin true color: what an assertion on escape sequences sees should not
  ;; depend on the terminal the suite happens to run under.
  (ffi/set-color-support 3)
  (let [loaded (doall
                 (for [ns namespaces
                       :let [ok (try (require ns :reload) true
                                     (catch Exception e
                                       (println "ERROR requiring" ns ":" (ex-message e))
                                       (when-let [d (ex-data e)] (prn d))
                                       false))]
                       :when ok]
                   ns))
        results (apply t/run-tests loaded)
        failed  (+ (:fail results 0) (:error results 0)
                   (- (count namespaces) (count loaded)))]
    (println "----")
    (println "tests:" (:test results 0)
             "assertions:" (:pass results 0) "passed /"
             failed "failed")
    (when (pos? failed) (exit 1))))
