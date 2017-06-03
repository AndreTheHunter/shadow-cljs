(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [shadow.runtime.services :as rt]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.config :as config]
            [clojure.repl :as repl]))

;; use namespaced keywords for every CLI specific option
;; since all options are passed to the api/* and should not conflict there

(defn mode-cli-opt [opt description]
  [nil opt description
   :assoc-fn
   (fn [m k v]
     (when-let [mode (get m ::mode)]
       (println (format "overwriting mode %s -> %s, please only use one mode" mode k)))
     (assoc m ::mode k))])

(def cli-spec
  [(mode-cli-opt "--dev" "mode: dev (will watch files and recompile, REPL, ...)")
   (mode-cli-opt "--once" "mode: compile once and exit")
   (mode-cli-opt "--release" "mode: compile release version and exit")
   (mode-cli-opt "--check" "mode: closure compiler type check and exit")
   (mode-cli-opt "--server" "[WIP] server mode, doesn't do much yet")

   ;; exlusive
   ["-b" "--build BUILD-ID" "use build defined in shadow-cljs.edn"
    :id ::build
    :parse-fn keyword]
   [nil "--npm" "internal, used by the shadow-cljs npm package"
    :id ::npm]

   ;; generic
   [nil "--debug" "enable debug options, useful in combo with --release (pseudo-names, source-map)"]
   ["-v" "--verbose"]
   ["-h" "--help"
    :id ::help]])

(def default-opts
  {:autobuild true})

(defn help [{:keys [errors summary] :as opts}]
  (do (doseq [err errors]
        (println err))
      (println "Command line args:")
      (println "-----")
      (println summary)
      (println "-----")))

(def default-npm-config
  {:id :npm
   :target :npm-module
   :runtime :node
   :output-dir "node_modules/shadow-cljs"})

(defn invoke
  "invokes a fn by requiring the namespace and looking up the var"
  [sym & args]
  ;; doing the delayed (require ...) so things are only loaded
  ;; when a path is reached as they load a bunch of code other paths
  ;; do not use, greatly improves startup time
  (let [require-ns (symbol (namespace sym))]
    (require require-ns)
    (let [fn-var (find-var sym)]
      (apply fn-var args)
      )))

(defn main [& args]
  (try
    (let [{:keys [options summary errors] :as opts}
          (cli/parse-opts args cli-spec)

          options
          (merge default-opts options)]

      (cond
        (or (::help options) (seq errors))
        (help opts)

        (= :server (::mode options))
        (invoke 'shadow.cljs.devtools.server/-main)

        :else
        (let [{::keys [build npm]} options

              build-config
              (cond
                (keyword? build)
                (config/get-build! build)

                npm
                (merge default-npm-config (config/get-build :npm))

                :else
                nil)]

          (if-not (some? build-config)
            (do (println "Please use specify a build or use --npm")
                (help opts))

            (case (::mode options)
              :release
              (invoke 'shadow.cljs.devtools.api/release* build-config options)

              :check
              (invoke 'shadow.cljs.devtools.api/check* build-config options)

              :dev
              (invoke 'shadow.cljs.devtools.api/dev* build-config options)

              ;; make :once the default
              (invoke 'shadow.cljs.devtools.api/once* build-config options)
              )))))

    (catch Exception e
      (try
        (invoke 'shadow.cljs.devtools.errors/user-friendly-error e)
        (catch Exception e2
          (println "failed to format error because of:")
          (repl/pst e2)
          (flush)
          (println "actual error:")
          (repl/pst e)
          (flush)
          )))))

(defn -main [& args]
  (apply main args))

(comment
  ;; FIXME: fix these properly and create CLI args for them
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