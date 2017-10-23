(ns shadow.cljs.devtools.api
  (:refer-clojure :exclude (compile))
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aleph.netty :as netty]
            [aleph.http :as aleph]
            [shadow.repl :as r]
            [shadow.runtime.services :as rt]
            [shadow.build :as build]
            [shadow.build.api :as build-api]
            [shadow.build.node :as node]
            [shadow.cljs.util :as cljs-util]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.server.web.common :as web-common]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as e]
            [shadow.cljs.devtools.server.worker.ws :as ws]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.build.npm :as npm]
            [shadow.build.classpath :as cp])
  (:import (java.io PushbackReader StringReader)
           (java.lang ProcessBuilder$Redirect)))

;; nREPL support

(def ^:dynamic *nrepl-cljs* nil)
(def ^:dynamic *nrepl-active* false)

(defn get-worker
  [id]
  {:pre [(keyword? id)]}
  (let [{:keys [out supervisor] :as app}
        (runtime/get-instance!)]
    (super/get-worker supervisor id)
    ))

(defn- new-build [{:keys [build-id] :or {build-id :custom} :as build-config} mode opts]
  (let [{:keys [npm classpath cache-root executor]}
        (or (runtime/get-instance)

            ;; FIXME: this should be done by shadow.cljs.devtools.cli
            ;; shadow-cljs release multiple builds foo bar
            ;; otherwise each build repeats this work since this is not shared
            (let [{:keys [cache-root] :as config}
                  (config/load-cljs-edn!)

                  cache-root
                  (io/file cache-root)]

              ;; FIXME: add executor but not sure where to call shutdown
              {:cache-root
               cache-root

               :npm
               (npm/start)

               :classpath
               (-> (cp/start (io/file cache-root "classpath-cache"))
                   (cp/index-classpath))}))

        cache-dir
        (config/make-cache-dir cache-root build-id mode)]

    (-> (build-api/init)
        (build-api/with-npm npm)
        (build-api/with-classpath classpath)
        (build-api/with-cache-dir cache-dir)
        (assoc :mode mode)
        (cond->
          executor
          (build-api/with-executor executor)))))

(defn get-or-start-worker [build-config opts]
  (let [{:keys [autobuild]}
        opts

        {:keys [out supervisor] :as app}
        (runtime/get-instance!)]

    (if-let [worker (super/get-worker supervisor (:build-id build-config))]
      worker
      (super/start-worker supervisor build-config)
      )))

(defn watch
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"
  ([build-id]
   (watch build-id {}))
  ([build-id {:keys [autobuild verbose] :as opts}]
   (let [out
         (util/stdout-dump verbose)

         build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))]

     (-> (get-or-start-worker build-config opts)
         (worker/watch out true)
         (cond->
           (not (false? autobuild))
           (-> (worker/start-autobuild)
               (worker/sync!))))

     :watching
     )))

(defn active-builds []
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (super/active-builds supervisor)))

(defn compiler-env [build-id]
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (when-let [worker (super/get-worker supervisor build-id)]
      (-> worker
          :state-ref
          (deref)
          :build-state
          :compiler-env))))

(comment
  (watch :browser)
  (stop-worker :browser))

(defn stop-worker [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]
    (super/stop-worker supervisor build-id)
    :stopped))

(defn node-repl
  ([]
   (node-repl {}))
  ([opts]
   (repl-impl/node-repl* (runtime/get-instance!) opts)))

(defn get-build-config [id]
  {:pre [(keyword? id)]}
  (config/get-build! id))

(defn build-finish [{::build/keys [build-info] :as state} config]
  (util/print-build-complete build-info config)
  state)

(defn compile* [build-config opts]
  (util/print-build-start build-config)
  (-> (new-build build-config :dev opts)
      (build/configure :dev build-config)
      (build/compile)
      (build/flush)
      (build-finish build-config)))

(defn compile!
  "do not use at the REPL, will return big pile of build state, will blow up REPL by printing too much"
  [build opts]
  (let [build-config (config/get-build! build)]
    (compile* build-config opts)))

(defn compile
  ([build]
   (compile build {}))
  ([build opts]
   (try
     (compile! build opts)
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn once
  "deprecated: use compile"
  ([build]
   (compile build {}))
  ([build opts]
   (compile build opts)))

(defn release*
  [build-config {:keys [debug source-maps pseudo-names] :as opts}]
  (util/print-build-start build-config)
  (-> (new-build build-config :release opts)
      (build/configure :release build-config)
      (cond->
        (or debug source-maps)
        (build-api/enable-source-maps)

        (or debug pseudo-names)
        (build-api/with-compiler-options
          {:pretty-print true
           :pseudo-names true}))
      (build/compile)
      (build/optimize)
      (build/flush)
      (build-finish build-config)))

(defn release
  ([build]
   (release build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (release* build-config opts))
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn check* [{:keys [id] :as build-config} opts]
  ;; FIXME: pretend release mode so targets don't need to account for extra mode
  ;; in most cases we want exactly :release but not sure that is true for everything?
  (-> (new-build build-config :release opts)
      (build/configure :release build-config)
      ;; using another dir because of source maps
      ;; not sure :release builds want to enable source maps by default
      ;; so running check on the release dir would cause a recompile which is annoying
      ;; but check errors are really useless without source maps
      (as-> X
        (-> X
            (assoc :cache-dir (io/file (:cache-dir X) "check"))
            ;; always override :output-dir since check output should never be used
            ;; only generates output for source maps anyways
            (assoc :output-dir (io/file (:cache-dir X) "check-out"))))
      (build-api/enable-source-maps)
      (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
      (build/compile)
      (build/check))
  :done)

(defn check
  ([build]
   (check build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (check* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn nrepl-select [id]
  (if-not (get-worker id)
    [:no-worker id]
    (do (set! *nrepl-cljs* id)
        ;; required for prompt?
        ;; don't actually need to do this
        (set! *ns* 'cljs.user)
        (println "To quit, type: :repl/quit")
        [:selected id])))

(defn repl [build-id]
  (if *nrepl-active*
    (nrepl-select build-id)
    (let [{:keys [supervisor] :as app}
          (runtime/get-instance!)

          worker
          (super/get-worker supervisor build-id)]
      (if-not worker
        :no-worker
        (repl-impl/stdin-takeover! worker app)))))

(defn dev*
  [build-config {:keys [autobuild verbose] :as opts}]
  (let [config
        (config/load-cljs-edn)

        {:keys [supervisor] :as app}
        (runtime/get-instance!)

        out
        (util/stdout-dump verbose)

        worker
        (-> (get-or-start-worker build-config opts)
            (worker/watch out false)
            (cond->
              (not (false? autobuild))
              (-> (worker/start-autobuild)
                  (worker/sync!))))]

    ;; for normal REPL loops we wait for the CLJS loop to end
    (when-not *nrepl-active*
      (repl-impl/stdin-takeover! worker app)
      (super/stop-worker supervisor (:build-id build-config)))

    :done))

(defn dev
  ([build]
   (dev build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (dev* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))

(comment
  (defn test-setup []
    (-> (build-api/init)
        (build-api/enable-source-maps)
        (as-> X
          (build-api/with-build-options X
            {:output-dir (io/file (:cache-dir X) "test-out")}))
        ))

  (defn node-execute! [node-args file]
    (let [script-args
          ["node"]

          pb
          (doto (ProcessBuilder. script-args)
            (.directory nil))]


      ;; not using this because we only get output once it is done
      ;; I prefer to see progress
      ;; (prn (apply shell/sh script-args))

      (let [node-proc (.start pb)]

        (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
        (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

        (let [out (.getOutputStream node-proc)]
          (io/copy (io/file file) out)
          (.close out))

        ;; FIXME: what if this doesn't terminate?
        (let [exit-code (.waitFor node-proc)]
          exit-code))))

  (defn test-all []
    (-> (build/configure :dev '{:build-id :shadow-build-api/test
                                :target :node-script
                                :main shadow.test-runner/main
                                :output-to "target/shadow-test-runner.js"
                                :hashbang false})
        (node/make-test-runner)
        (build/compile)
        (build/flush))

    (node-execute! [] "target/shadow-test-runner.js")
    ::test-all)

  (defn test-affected
    [source-names]
    {:pre [(seq source-names)
           (not (string? source-names))
           (every? string? source-names)]}
    (-> (test-setup)
        (node/execute-affected-tests! source-names))
    ::test-affected))


