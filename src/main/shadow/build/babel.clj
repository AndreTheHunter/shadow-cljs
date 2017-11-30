(ns shadow.build.babel
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async :refer (<!! >!!)]
            [shadow.build.log :as cljs-log]
            [shadow.cljs.util :as util]
            [clojure.tools.logging :as log])
  (:import (java.io PushbackReader Writer InputStreamReader BufferedReader IOException)))

(def worker-file
  "node_modules/shadow-cljs/cli/dist/shadow.cljs.npm.transform.js"
  #_"packages/shadow-cljs/cli/transform.js"
  )

(defn service? [x]
  (and (map? x) (::service x)))

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

(defn maybe-start-proc [{:keys [proc] :as state}]
  (if (and proc (.isAlive proc))
    state
    (let [proc
          (-> (ProcessBuilder.
                (into-array ["node" worker-file]))
              (.directory nil)
              (.start))]

      ;; FIXME: errors need to go somewhere else, this is not reliable
      (.start (Thread. (bound-fn [] (pipe proc (.getErrorStream proc) *err*))))

      (assoc state
        :proc proc
        :in (PushbackReader. (io/reader (.getInputStream proc)))
        :out (io/writer (.getOutputStream proc)))
      )))

(defn babel-transform! [state {::keys [reply-to] :as req}]
  (try
    (let [{:keys [proc in out] :as state} (maybe-start-proc state)]
      ;; send request as one-line edn
      (let [line
            (-> req
                (dissoc ::reply-to)
                (pr-str)
                (str "\n"))]
        (doto out
          (.write line)
          (.flush)))

      ;; read one line
      (let [res (read in)]
        (>!! reply-to res))
      state)
    (catch Exception e
      (log/warnf e "babel-transform! failed")
      state)
    (finally
      (async/close! reply-to))))

(defn shutdown [{:keys [proc in out] :as state}]
  (when (and proc (.isAlive proc))
    (.close in)
    (.close out)
    (.destroy proc)
    (.waitFor proc)))

(defn babel-loop [babel-in]
  (loop [state {}]
    (if-some [req (<!! babel-in)]
      (recur (babel-transform! state req))
      (shutdown state))))

(defn start []
  (when-not (.exists (io/file worker-file))
    (log/warnf "can't find %s, please install npm install --save-dev shadow-cljs." worker-file))

  (let [babel-in (async/chan 100)]
    {::service true
     :babel-in babel-in
     :babel-loop (async/thread (babel-loop babel-in))}))

(defn stop [{:keys [babel-in babel-loop] :as svc}]
  (async/close! babel-in)
  (<!! babel-loop))

(defn transform [{:keys [babel-in] :as svc} req]
  {:pre [(map? req)
         (contains? req :code)
         (contains? req :resource-name)]}
  (let [reply-to (async/chan 1)
        req (assoc req ::reply-to reply-to)]
    (>!! babel-in req)
    (<!! reply-to)))

(defmethod cljs-log/event->str ::transform
  [{:keys [resource-name] :as event}]
  (format "Babel transform: %s " resource-name))

(defn convert-source [babel state source resource-name]
  {:pre [(service? babel)
         (string? source)
         (string? resource-name)]}
  (util/with-logged-time [state {:type ::transform
                                 :resource-name resource-name}]

    (let [{:keys [code] :as result}
          (transform babel {:code source
                            :resource-name resource-name})]
      (when-not (seq code)
        (throw (ex-info "babel failed?" (assoc result :resource-name resource-name))))

      code
      )))

(comment
  (let [{:keys [babel-in] :as svc} (start)]
    (prn [:started])

    (dotimes [x 10]
      (prn (transform svc {:code "let foo = 1;" :resource-name "test.js"})))

    (stop svc)
    (prn [:done])
    :done))
