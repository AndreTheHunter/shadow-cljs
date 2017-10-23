(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.repl :as repl]
            [clojure.main :as main]
            [aleph.http :as aleph]
            [aleph.netty :as netty]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.server.worker.ws :as ws]
            [shadow.cljs.devtools.server.web.common :as web-common]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cli-opts :as opts]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.http.router :as http]
            [shadow.build.api :as cljs]
            [shadow.build.node :as node]
            [shadow.cljs.devtools.server.socket-repl :as socket-repl])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io StringReader)))

(defn do-build-command [{:keys [action options] :as opts} build-config]
  (try
    (case action
      :release
      (api/release* build-config options)

      :check
      (api/check* build-config options)

      :compile
      (api/compile* build-config options))

    :success
    (catch Exception e
      e)))

(defn maybe-rethrow-exceptions [ex]
  (case (count ex)
    0 :success
    1 (throw (first ex))
    (throw (ex-info "multiple builds failed" {:exceptions ex}))
    ))

(defn do-build-commands
  [{:keys [builds] :as config} {:keys [action options] :as opts} build-ids]
  ;; FIXME: this should start classpath/npm services so builds can use it
  ;; if this is not a server instance

  (->> build-ids
       (map (fn [build-id]
              (or (get builds build-id)
                  (do (println (str "No config for build \"" (name build-id) "\" found."))
                      ;; FIXME: don't repeat this
                      (println (str "Available builds are: " (->> builds
                                                                  (keys)
                                                                  (map name)
                                                                  (str/join ", "))))))))
       (remove nil?)
       (map #(future (do-build-command opts %)))
       (into []) ;; force lazy seq
       (map deref)
       (remove #{:success})
       (into []) ;; force again
       ;; need to throw exceptions to ensure that cli commands can exit with proper exit codes
       (maybe-rethrow-exceptions)))

(defn do-clj-eval [config {:keys [arguments options] :as opts}]
  (let [in
        (if (:stdin options)
          *in*
          (-> (str/join " " arguments)
              (StringReader.)
              (LineNumberingPushbackReader.)))]

    (binding [*in* in]
      (main/repl
        :init #(socket-repl/repl-init {:print false})
        :prompt (fn [])))
    ))

(defn main [& args]
  (let [{:keys [action builds options summary errors] :as opts}
        (opts/parse args)

        config
        (config/load-cljs-edn!)]

    ;; always attempt to install npm-deps?
    ;; doesn't do anything if all deps are in package.json
    (npm-deps/main config opts)

    (cond
      (:version options)
      (println "TBD")

      (or (:help options) (seq errors))
      (opts/help opts)

      ;;(= action :test)
      ;;(api/test-all)

      (= :npm-deps action)
      (println "npm-deps done.")

      (= :clj-eval action)
      (do-clj-eval config opts)

      (contains? #{:compile :check :release} action)
      (do-build-commands config opts builds)

      (contains? #{:watch :node-repl :cljs-repl :clj-repl :server} action)
      (server/from-cli action builds options))))

(defn print-main-error [e]
  (try
    (errors/user-friendly-error e)
    (catch Exception e2
      (println "failed to format error because of:")
      (repl/pst e2)
      (flush)
      (println "actual error:")
      (repl/pst e)
      (flush)
      )))

(defn print-token
  "attempts to print the given token to stdout which may be a socket
   if the client already closed the socket that would cause a SocketException
   so we ignore any errors since the client is already gone"
  [token]
  (try
    (println token)
    (catch Exception e
      ;; CTRL+D closes socket so we can't write to it anymore
      )))

(defn from-remote
  "the CLI script calls this with 2 extra token (uuids) that will be printed to notify
   the script whether or not to exit with an error code, it also causes the client to
   disconnect instead of us forcibly dropping the connection"
  [complete-token error-token args]
  (try
    (apply main "--via" "remote" args)
    (print-token complete-token)
    (catch Exception e
      (print-main-error e)
      (println error-token))))

;; direct launches don't need to mess with tokens
(defn -main [& args]
  (try
    (apply main "--via" "main" args)
    (shutdown-agents)
    (catch Exception e
      (print-main-error e)
      (System/exit 1))))

(comment
  (defn autotest
    "no way to interrupt this, don't run this in nREPL"
    []
    (-> (api/test-setup)
        (cljs/watch-and-repeat!
          (fn [state modified]
            (-> state
                (cond->
                  ;; first pass, run all tests
                  (empty? modified)
                  (node/execute-all-tests!)
                  ;; only execute tests that might have been affected by the modified files
                  (not (empty? modified))
                  (node/execute-affected-tests! modified))
                )))))

  (defn test-all []
    (api/test-all))

  (defn test-affected [test-ns]
    (api/test-affected [(cljs/ns->cljs-file test-ns)])))