(ns shadow.build.targets.shared
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as cljs]
            [shadow.build.data :as data]
            [shadow.build.modules :as modules]
            )
  (:import [java.net InetAddress]))

(defn unquoted-qualified-symbol? [sym]
  (and (qualified-symbol? sym)
       (not (str/starts-with? (str sym) "'"))))

(defn unquoted-simple-symbol? [sym]
  (and (simple-symbol? sym)
       (not (str/starts-with? (str sym) "'"))))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::output-dir non-empty-string?)

(s/def ::output-to non-empty-string?)

(s/def ::http-root non-empty-string?)

(s/def ::http-port pos-int?)

(s/def ::http-handler unquoted-qualified-symbol?)

(s/def ::enabled boolean?)

(s/def ::autoload boolean?)

(s/def ::after-load unquoted-qualified-symbol?)

(s/def ::before-load unquoted-qualified-symbol?)

(s/def ::before-load-async unquoted-qualified-symbol?)

(s/def ::devtools-url non-empty-string?)

(s/def ::use-document-host boolean?)

(s/def ::devtools
  (s/keys
    :opt-un
    [::http-root
     ::http-port
     ::http-handler
     ::enabled
     ::autoload
     ::after-load
     ::before-load
     ::before-load-async
     ::use-document-host
     ::devtools-url]))

(defn prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn repl-defines
  [{:keys [worker-info] :as state} build-config]
  (let [{:keys [proc-id ssl host port]}
        worker-info

        {:keys [build-id]}
        build-config

        {:keys [ignore-warnings
                devtools-url
                before-load
                before-load-async
                after-load
                autoload
                use-document-host
                repl-pprint]}
        (:devtools build-config)]

    {'shadow.cljs.devtools.client.env/enabled
     true

     'shadow.cljs.devtools.client.env/autoload
     (or autoload (some? before-load) (some? after-load) (some? before-load-async))

     'shadow.cljs.devtools.client.env/module-format
     (name (get-in state [:build-options :module-format]))

     'shadow.cljs.devtools.client.env/use-document-host
     (not (false? use-document-host))

     'shadow.cljs.devtools.client.env/server-host
     (or host "localhost")

     'shadow.cljs.devtools.client.env/server-port
     port

     'shadow.cljs.devtools.client.env/repl-pprint
     (true? repl-pprint)

     'shadow.cljs.devtools.client.env/ignore-warnings
     (true? ignore-warnings)

     'shadow.cljs.devtools.client.env/ssl
     (true? ssl)

     'shadow.cljs.devtools.client.env/build-id
     (name build-id)

     'shadow.cljs.devtools.client.env/proc-id
     (str proc-id)

     'shadow.cljs.devtools.client.env/devtools-url
     (or devtools-url "")
     }))

(defn merge-repl-defines [state config]
  (update-in state [:compiler-options :closure-defines] merge (repl-defines state config)))

(defn inject-node-repl
  [state {:keys [devtools] :as config}]
  (if (false? (:enabled devtools))
    state
    (-> state
        (update-in [:compiler-options :closure-defines] merge (repl-defines state config))
        (update-in [::modules/config :main :entries] prepend '[cljs.user shadow.cljs.devtools.client.node])
        )))

(defn set-output-dir [state mode {:keys [id output-dir] :as config}]
  (-> state
      (cond->
        (seq output-dir)
        (assoc-in [:build-options :output-dir] (io/file output-dir))

        (not (seq output-dir))
        (assoc-in [:build-options :output-dir] (data/cache-file state "out")))))

