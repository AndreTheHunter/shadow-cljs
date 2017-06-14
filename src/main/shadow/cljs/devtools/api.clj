(ns shadow.cljs.devtools.api
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as e]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.server.web.common :as web-common]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            [aleph.netty :as netty]
            [aleph.http :as aleph]
            [shadow.cljs.devtools.server.worker.ws :as ws]
            [clojure.string :as str]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.cljs.devtools.server.runtime :as runtime]
            )
  (:import (java.io PushbackReader StringReader)))

(defn get-or-start-worker [build-config opts]
  (let [{:keys [autobuild]}
        opts

        {:keys [out supervisor] :as app}
        (runtime/get-instance!)]

    (if-let [worker (super/get-worker supervisor (:id build-config))]
      worker
      (-> (super/start-worker supervisor build-config)
          (worker/watch out false)
          (cond->
            autobuild
            (worker/start-autobuild)
            )))))

(defn start-worker
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"
  ([build-id]
   (start-worker build-id {:autobuild true}))
  ([build-id opts]
   (let [{:keys [autobuild]}
         opts

         build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))]

     (get-or-start-worker build-config opts))
   :started))

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

(defn dev*
  [build-config {:keys [autobuild] :as opts}]
  (let [config
        (config/load-cljs-edn)

        {:keys [supervisor] :as app}
        (runtime/get-instance!)]

    (try
      (-> (get-or-start-worker build-config opts)
          (worker/sync!)
          (repl-impl/stdin-takeover! app))

      (super/stop-worker supervisor (:id build-config))
      :done
      (catch Exception e
        (e/user-friendly-error e)))
    ))

(defn dev
  ([build]
   (dev build {:autobuild true}))
  ([build {:keys [autobuild] :as opts}]
   (let [build-config (config/get-build! build)]
     (dev* build-config opts))))

(defn build-finish [{::comp/keys [build-info] :as state} config]
  (util/print-build-complete build-info config)
  state)

(defn once* [build-config opts]
  (try
    (util/print-build-start build-config)
    (-> (comp/init :dev build-config)
        (comp/compile)
        (comp/flush)
        (build-finish build-config))
    :done
    (catch Exception e
      (e/user-friendly-error e))
    ))

(defn once
  ([build]
   (once build {}))
  ([build opts]
   (let [build-config (config/get-build! build)]
     (once* build-config opts)
     )))

(defn release*
  [build-config {:keys [debug source-maps pseudo-names] :as opts}]
  (try
    (util/print-build-start build-config)
    (-> (comp/init :release build-config)
        (cond->
          (or debug source-maps)
          (cljs/enable-source-maps)

          (or debug pseudo-names)
          (cljs/merge-compiler-options
            {:pretty-print true
             :pseudo-names true}))
        (comp/compile)
        (comp/optimize)
        (comp/flush)
        (build-finish build-config))
    :done
    (catch Exception e
      (e/user-friendly-error e))))

(defn release
  ([build]
   (release build {}))
  ([build opts]
   (let [build-config (config/get-build! build)]
     (release* build-config opts))))

(defn check* [{:keys [id] :as build-config} opts]
  (try
    ;; FIXME: pretend release mode so targets don't need to account for extra mode
    ;; in most cases we want exactly :release but not sure that is true for everything?
    (-> (comp/init :release build-config)
        ;; using another dir because of source maps
        ;; not sure :release builds want to enable source maps by default
        ;; so running check on the release dir would cause a recompile which is annoying
        ;; but check errors are really useless without source maps
        (as-> X
          (-> X
              (assoc :cache-dir (io/file (:work-dir X) "shadow-cljs" (name id) "check"))
              ;; always override :output-dir since check output should never be used
              ;; only generates output for source maps anyways
              (assoc :output-dir (io/file (:work-dir X) "shadow-cljs" (name id) "check" "output"))))
        (cljs/enable-source-maps)
        (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
        (comp/compile)
        (comp/check))
    :done
    (catch Exception e
      (e/user-friendly-error e))))

(defn check
  ([build]
   (check build {}))
  ([build opts]
   (let [build-config (config/get-build! build)]
     (check* build-config opts)
     )))

(defn repl [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)

        worker
        (super/get-worker supervisor build-id)]
    (if-not worker
      :no-worker
      (repl-impl/stdin-takeover! worker app))))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))

(comment

  (defn test-setup []
    (-> (cljs/init-state)
        (cljs/enable-source-maps)
        (as-> X
          (cljs/merge-build-options X
            {:output-dir (io/file (:work-dir X) "shadow-test")
             :asset-path "target/shadow-test"}))
        (cljs/find-resources-in-classpath)
        ))

  (defn test-all []
    (-> (test-setup)
        (node/execute-all-tests!))
    ::test-all)

  (defn test-affected
    [source-names]
    {:pre [(seq source-names)
           (not (string? source-names))
           (every? string? source-names)]}
    (-> (test-setup)
        (node/execute-affected-tests! source-names))
    ::test-affected))