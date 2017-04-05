(ns shadow.cljs.devtools.server.util
  (:require [shadow.cljs.log :as shadow-log]
            [clojure.core.async :as async :refer (go thread <! >! alt!! alts!!)]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.build :as cljs])
  (:import (java.io Writer InputStreamReader BufferedReader IOException)))

(defn async-logger [ch]
  (reify
    shadow-log/BuildLog
    (log*
      [_ state event]
      (async/offer! ch {:type :build-log
                        :event event}))))

(def null-log
  (reify
    shadow-log/BuildLog
    (log* [_ state msg])))

(defn print-warnings [warnings]
  (doseq [{:keys [msg line column source-name] :as w} warnings]
    (println (str "WARNING: " msg " (" (or source-name "<stdin>") " at " line ":" column ")"))))

(defn print-worker-out [x verbose]
  (locking cljs/stdout-lock
    (binding [*out* *err*]
      (case (:type x)
        :build-log
        (when verbose
          (println (shadow-log/event-text (:event x))))

        :build-start
        (println (format "[%s] Build started." (-> x :build-config :id)))

        :build-complete
        (let [{:keys [info build-config]} x
              {:keys [sources compiled warnings]} info]

          (println (format "[%s] Build completed. (%d files, %d compiled, %d warnings)"
                     (:id build-config)
                     (count sources)
                     (count compiled)
                     (count warnings)))

          (when (seq warnings)
            (println (format "====== %d Warnings" (count warnings)))
            (doseq [{:keys [msg line column source-name] :as w} warnings]
              (println (str "WARNING: " msg " (" source-name " at " line ":" column ") ")))
            (println "======")))

        :build-shutdown
        (println "Build shutdown.")

        :repl/action
        (let [warnings (get-in x [:action :warnings])]
          (print-warnings warnings))

        ;; should have been handled somewhere else
        :repl/result
        :ignored

        :repl/error
        :ignored ;; also handled by out

        :repl/eval-start
        (println "JS runtime connected.")

        :repl/eval-stop
        (println "JS runtime disconnected.")

        :worker-shutdown
        (println "Worker shutdown.")

        ;; default
        (prn [:log x])))))

(defn stdout-dump [verbose]
  (let [chan
        (-> (async/sliding-buffer 1)
            (async/chan))]

    (async/go
      (loop []
        (when-some [x (<! chan)]
          (print-worker-out x verbose)
          (recur)
          )))

    chan
    ))

(defn server-thread
  [state-ref init-state dispatch-table {:keys [do-shutdown] :as options}]
  (let [chans
        (into [] (keys dispatch-table))]

    (thread
      (let [last-state
            (loop [state
                   init-state]
              (vreset! state-ref state)

              (let [[msg ch]
                    (alts!! chans)

                    handler
                    (get dispatch-table ch)]

                (if (nil? handler)
                  state
                  (if (nil? msg)
                    state
                    (let [state
                          (try
                            (handler state msg)
                            (catch Exception e
                              (prn [:error-occured-in-server e])
                              state))]
                      (recur state))
                    ))))]

        (if-not do-shutdown
          last-state
          (do-shutdown last-state)
          )))))

;; https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/repl/node.clj
;; I would just call that but it is private ...
(defn pipe [^Process proc in ^Writer out]
  ;; we really do want system-default encoding here
  (with-open [^java.io.Reader in (-> in InputStreamReader. BufferedReader.)]
    (loop [buf (char-array 1024)]
      (when (.isAlive proc)
        (try
          (let [len (.read in buf)]
            (when-not (neg? len)
              (.write out buf 0 len)
              (.flush out)))
          (catch IOException e
            (when (and (.isAlive proc) (not (.contains (.getMessage e) "Stream closed")))
              (.printStackTrace e *err*))))
        (recur buf)))))


