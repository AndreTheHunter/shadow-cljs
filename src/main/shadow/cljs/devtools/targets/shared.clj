(ns shadow.cljs.devtools.targets.shared
  (:require [clojure.spec :as s]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::public-dir non-empty-string?)

(s/def ::output-to non-empty-string?)

(defn prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn repl-defines
  [{:keys [worker-info] :as state} build-config]
  (let [{:keys [proc-id host port]}
        worker-info

        {:keys [id]}
        build-config

        {:keys [reload-with-state before-load after-load autoload]}
        (:devtools build-config)]

    {"shadow.cljs.devtools.client.env.enabled"
     true

     "shadow.cljs.devtools.client.env.autoload"
     (or autoload (some? before-load) (some? after-load))

     "shadow.cljs.devtools.client.env.repl_host"
     host

     "shadow.cljs.devtools.client.env.repl_port"
     port

     "shadow.cljs.devtools.client.env.build_id"
     (name id)

     "shadow.cljs.devtools.client.env.proc_id"
     (str proc-id)

     "shadow.cljs.devtools.client.env.before_load"
     (when before-load
       (str (cljs-comp/munge before-load)))

     "shadow.cljs.devtools.client.env.after_load"
     (when after-load
       (str (cljs-comp/munge after-load)))

     "shadow.cljs.devtools.client.env.reload_with_state"
     (boolean reload-with-state)
     }))


(defn inject-node-repl
  [state config]
  (-> state
      (update :closure-defines merge (repl-defines state config))
      (update-in [:modules (:default-module state) :entries] prepend '[cljs.user shadow.cljs.devtools.client.node])
      ))


