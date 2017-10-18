(ns shadow.build.output
  (:require [clojure.java.io :as io]
            [cljs.source-map :as sm]
            [shadow.cljs.util :as util]
            [clojure.string :as str]
            [cljs.compiler :as comp]
            [clojure.data.json :as json]
            [shadow.build.data :as data]
            [clojure.set :as set])
  (:import (java.io StringReader BufferedReader File)
           (java.util Base64)))

(defn closure-defines-json [state]
  (let [closure-defines
        (reduce-kv
          (fn [def key value]
            (let [key (if (symbol? key) (str (comp/munge key)) key)]
              (assoc def key value)))
          {}
          (get-in state [:compiler-options :closure-defines] {}))]

    (json/write-str closure-defines :escape-slashes false)))

(defn closure-defines [{:keys [build-options] :as state}]
  (let [{:keys [asset-path cljs-runtime-path]} build-options]
    (str "var CLOSURE_NO_DEPS = true;\n"
         ;; goog.findBasePath_() requires a base.js which we dont have
         ;; this is usually only needed for unoptimized builds anyways
         "var CLOSURE_BASE_PATH = '" asset-path "/" cljs-runtime-path "/';\n"
         "var CLOSURE_DEFINES = " (closure-defines-json state) ";\n")))

(def goog-base-id
  ;; can't alias due to cyclic dependency, this sucks
  ;; goog/base.js is treated special in several cases
  [:shadow.build.classpath/resource "goog/base.js"])

(defn closure-defines-and-base [{:keys [asset-path cljs-runtime-path] :as state}]
  (let [goog-rc (get-in state [:sources goog-base-id])
        goog-base (get-in state [:output goog-base-id :js])]

    (when-not (seq goog-base)
      (throw (ex-info "no goog/base.js" {})))

    ;; FIXME: work arround for older cljs versions that used broked closure release, remove.
    (when (< (count goog-base) 500)
      (throw (ex-info "probably not the goog/base.js you were expecting"
               (get-in state [:sources goog-base-id]))))

    (str (closure-defines state)
         goog-base
         "\n")))

(defn- ns-list-string [coll]
  (->> coll
       (map #(str "'" (comp/munge %) "'"))
       (str/join ",")))

(defn directory? [^File x]
  (and (instance? File x)
       (or (not (.exists x))
           (.isDirectory x))))

(defn closure-goog-deps
  ([state]
   (closure-goog-deps state (-> state :sources keys)))
  ([state source-ids]
   (->> source-ids
        (remove #{goog-base-id})
        (map #(get-in state [:sources %]))
        (map (fn [{:keys [output-name deps provides] :as rc}]
               (str "goog.addDependency(\"" output-name "\",["
                    (ns-list-string provides)
                    "],["
                    (->> (data/deps->syms state rc)
                         (remove '#{goog})
                         (ns-list-string))
                    "]);")))
        (str/join "\n"))))

;; FIXME: this could inline everything from a jar since they will never be live-reloaded
;; but it would need to create a proper source-map for the module file
;; since we need that for CLJS files
(defn inlineable? [{:keys [type from-jar provides requires] :as src}]
  ;; only inline things from jars
  (and from-jar
       ;; only js is inlineable since we want proper source maps for cljs
       (= :js type)
       ;; only inline goog for now
       (every? #(str/starts-with? (str %) "goog") requires)
       (every? #(str/starts-with? (str %) "goog") provides)
       ))

(defn line-count [text]
  (with-open [rdr (io/reader (StringReader. text))]
    (count (line-seq rdr))))

(defn encode-source-map
  [{:keys [resource-name prepend output-name source] :as src}
   {:keys [source-map] :as output}]
  (let [sm-opts
        {;; :lines (line-count output)
         :file output-name
         :preamble-line-count (if-not (seq prepend)
                                0
                                (line-count prepend))
         :sources-content [source]}

        source-map-cljs
        (-> {output-name source-map}
            (sm/encode* sm-opts)
            (assoc "sources" [resource-name])
            ;; its nil which closure doesn't like
            (dissoc "lineCount"))]

    (json/write-str source-map-cljs)))

(defn generate-source-map-inline
  [state
   {:keys [resource-name output-name source] :as src}
   {:keys [source-map source-map-json] :as output}
   prepend]
  (when (or source-map source-map-json)

    (let [source-map-json
          (or source-map-json
              (encode-source-map src output))

          b64
          (-> (Base64/getEncoder)
              (.encodeToString (.getBytes source-map-json)))]

      (str "\n//# sourceMappingURL=data:application/json;charset=utf-8;base64," b64 "\n"))))

(defn generate-source-map
  [state
   {:keys [resource-name output-name input source] :as src}
   {:keys [source-map source-map-json] :as output}
   js-file
   prepend]
  (when (or source-map source-map-json)
    (let [sm-text
          (str "\n//# sourceMappingURL=" output-name ".map\n")

          src-map-file
          (io/file (str (.getAbsolutePath js-file) ".map"))

          source-map-json
          (or source-map-json
              (let [sm-opts
                    {;; :lines (line-count output)
                     :file output-name
                     :preamble-line-count (line-count prepend)
                     :sources-content [source]}

                    ;; yay or nay on using flat filenames for source maps?
                    ;; personally I don't like seeing only the filename without the path
                    source-map-v3
                    (-> {(util/flat-filename resource-name) source-map}
                        (sm/encode* sm-opts)
                        (dissoc "lineCount") ;; its nil which closure doesn't like
                        (assoc "sources" [resource-name])
                        )]

                (json/write-str source-map-v3 :escape-slash false)
                ))]
      (spit src-map-file source-map-json)

      sm-text)))

(defn flush-sources
  ([{:keys [build-sources] :as state}]
   (flush-sources state build-sources))
  ([{:keys [build-options] :as state} source-ids]
   (let [{:keys [cljs-runtime-path]} build-options

         required-js-names
         (data/js-names-accessed-from-cljs state source-ids)]

     (util/with-logged-time
       [state {:type :flush-sources
               :source-ids source-ids}]
       (doseq [src-id source-ids
               :let [{:keys [output-name ns last-modified] :as src}
                     (data/get-source-by-id state src-id)

                     {:keys [js] :as output}
                     (data/get-output! state src)

                     js-file
                     (data/output-file state cljs-runtime-path output-name)]

               ;; skip files we already have
               :when (or (not (.exists js-file))
                         (zero? last-modified)
                         ;; js is not compiled but maybe modified
                         (> (or (:compiled-at output) last-modified)
                            (.lastModified js-file)))]

         (io/make-parents js-file)

         (let [output (str js
                           (when (contains? required-js-names ns)
                             (str "\nvar " ns "=shadow.js.require(\"" ns "\");\n"))
                           (generate-source-map state src output js-file ""))]
           (spit js-file output)))

       state))))

(defn flush-optimized
  ;; FIXME: can't alias this due to cyclic dependency
  [{modules :shadow.build.closure/modules
    :keys [build-options]
    :as state}]

  (let [{:keys [module-format]} build-options]

    (when-not (seq modules)
      (throw (ex-info "flush before optimize?" {})))

    (when (= module-format :js)
      (let [env-file
            (data/output-file state "cljs_env.js")]

        (io/make-parents env-file)
        (spit env-file
          (str "module.exports = {};\n"))))

    (util/with-logged-time
      [state {:type :flush-optimized}]

      (doseq [{:keys [dead prepend output append source-map-name source-map-json module-id output-name] :as mod} modules]
        (if dead
          (util/log state {:type :dead-module
                           :module-id module-id
                           :output-name output-name})

          (let [target
                (data/output-file state output-name)

                source-map-name
                (str output-name ".map")

                ;; must not prepend anything else before output
                ;; will mess up source maps otherwise
                ;; append is fine
                final-output
                (str prepend
                     output
                     append
                     (when source-map-json
                       (str "\n//# sourceMappingURL=" source-map-name "\n")))]

            (io/make-parents target)

            (spit target final-output)

            (util/log state {:type :flush-module
                             :module-id module-id
                             :output-name output-name
                             :js-size (count final-output)})

            (when source-map-json
              (let [target (data/output-file state source-map-name)]
                (spit target source-map-json))))))

      ;; with-logged-time expects that we return the compiler-state
      state
      )))

(defn flush-unoptimized-module!
  [{:keys [dead-js-deps unoptimizable build-options] :as state}
   {:keys [goog-base output-name prepend append sources web-worker includes] :as mod}]

  (let [{:keys [dev-inline-js cljs-runtime-path asset-path]}
        build-options

        inlineable-sources
        (if-not dev-inline-js
          []
          (->> sources
               (map #(data/get-source-by-id state %))
               (filter inlineable?)
               (into [])))

        inlineable-set
        (into #{} (map :resource-id) inlineable-sources)

        target
        (data/output-file state output-name)

        inlined-js
        (->> inlineable-sources
             (map #(data/get-output! state %))
             (map :js)
             (str/join "\n"))

        inlined-provides
        (->> inlineable-sources
             (mapcat :provides)
             (into #{}))

        ;; goog.writeScript_ (via goog.require) will set these
        ;; since we skip these any later goog.require (that is not under our control, ie REPL)
        ;; won't recognize them as loaded and load again
        closure-require-hack
        (->> inlineable-sources
             (map :output-name)
             (map (fn [output-name]
                    ;; not entirely sure why we are setting the full path and just the name
                    ;; goog seems to do that
                    (str "goog.dependencies_.written[\"" output-name "\"] = true;\n"
                         "goog.dependencies_.written[\"" asset-path "/" cljs-runtime-path "/" output-name "\"] = true;")
                    ))
             (str/join "\n"))

        requires
        (->> sources
             (remove inlineable-set)
             (mapcat #(get-in state [:sources % :provides]))
             (distinct)
             (remove inlined-provides)
             (remove '#{goog})
             (remove dead-js-deps)
             (map (fn [ns]
                    (str "goog.require('" (comp/munge ns) "');")))
             (str/join "\n"))

        ;; includes are special since they bypass all of closure
        ;; for now these are files generated by browserify and then loaded here
        ;; or concatenated into the final file for optimized builds
        includes
        (when (seq includes)
          (str (->> includes
                    (map (fn [{:keys [name file]}]
                           (str "goog.writeScriptTag_(CLOSURE_BASE_PATH + \"" name "\");")))
                    (str/join "\n"))
               "\n")) ;; OCD alert

        out
        (str includes
             inlined-js
             prepend
             closure-require-hack
             requires
             append)

        out
        (if (or goog-base web-worker)
          ;; default mod needs closure related setup and goog.addDependency stuff
          (str unoptimizable
               (when web-worker
                 "\nvar CLOSURE_IMPORT_SCRIPT = function(src) { importScripts(src); };\n")
               (closure-defines-and-base state)
               ;; create the $CLJS var so devtools can always use it
               ;; always exists for :module-format :js
               "goog.global[\"$CLJS\"] = goog.global;\n"
               (closure-goog-deps state (:build-sources state))
               "\n\n"
               out)
          ;; else
          out)]

    (spit target out)))

(defn flush-unoptimized!
  [{:keys [build-modules] :as state}]

  ;; FIXME: this always flushes
  ;; it could do partial flushes when nothing was actually compiled
  ;; a change in :closure-defines won't trigger a recompile
  ;; so just checking if nothing was compiled is not reliable enough
  ;; flushing really isn't that expensive so just do it always

  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (flush-sources state)

  (util/with-logged-time
    [state {:type :flush-unoptimized}]

    (doseq [mod build-modules]
      (flush-unoptimized-module! state mod))

    state
    ))

(defn flush-unoptimized
  [state]
  "util for ->"
  (flush-unoptimized! state)
  state)

(defn js-module-root [sym]
  (let [s (comp/munge (str sym))]
    (if-let [idx (str/index-of s ".")]
      (subs s 0 idx)
      s)))

(defn js-module-src-prepend [state {:keys [resource-id resource-name output-name provides requires deps] :as src} require?]
  (let [dep-syms
        (data/deps->syms state src)

        roots
        (into #{"goog"} (map js-module-root) dep-syms)]

    (str (when require?
           "var $CLJS = require(\"./cljs_env\");\n")
         ;; the only actually global var goog sometimes uses that is not on goog.global
         ;; actually only: goog/promise/thenable.js goog/proto2/util.js?
         (when (str/starts-with? resource-name "goog")
           "var COMPILED = false;\n")

         (when require?
           ;; emit requires to actual files to ensure that they were loaded properly
           ;; can't ensure that the files were loaded before this as goog.require would
           (->> dep-syms
                (remove #{'goog})
                (map #(data/get-source-id-by-provide state %))
                (distinct)
                (map #(data/get-source-by-id state %))
                (remove util/foreign?)
                (map (fn [{:keys [output-name] :as x}]
                       (str "require(\"./" output-name "\");")))
                (str/join "\n")))
         "\n"

         ;; require roots will exist
         (->> roots
              (map (fn [root]
                     (str "var " root "=$CLJS." root ";")))
              (str/join "\n"))
         "\n"
         ;; provides may create new roots
         (->> provides
              (map js-module-root)
              (remove roots)
              (map (fn [root]
                     (str "var " root "=$CLJS." root " || ($CLJS." root " = {});")))
              (str/join "\n"))
         "\ngoog.dependencies_.written[" (pr-str output-name) "] = true;\n"
         "\n")))

(defn js-module-src-append [state {:keys [ns provides] :as src}]
  ;; export the shortest name always, some goog files have multiple provides
  (let [export
        (->> provides
             (map str)
             (sort)
             (map comp/munge)
             (first))]

    (str "\nmodule.exports = " export ";\n")))

(defn js-module-env
  [state {:keys [runtime] :or {runtime :node} :as config}]
  (str "var $CLJS = {};\n"
       "var CLJS_GLOBAL = process.browser ? window : global;\n"
       ;; closure accesses these defines via goog.global.CLOSURE_DEFINES
       "var CLOSURE_DEFINES = $CLJS.CLOSURE_DEFINES = " (closure-defines-json state) ";\n"
       "CLJS_GLOBAL.CLOSURE_NO_DEPS = true;\n"
       ;; so devtools can access it
       "CLJS_GLOBAL.$CLJS = $CLJS;\n"
       "var goog = $CLJS.goog = {};\n"
       ;; the global must be overriden in goog/base.js since it contains some
       ;; goog.define(...) which would otherwise be exported to "this"
       ;; but we need it on $CLJS
       (-> (data/get-output! state {:resource-id goog-base-id})
           (get :js)
           (str/replace "goog.global = this;" "goog.global = $CLJS;"))

       ;; set global back to actual global so things like setTimeout work
       "\ngoog.global = CLJS_GLOBAL;"

       (slurp (io/resource "shadow/build/targets/npm_module_goog_overrides.js"))
       "\nmodule.exports = $CLJS;\n"
       ))

(defn flush-dev-js-modules
  [{::comp/keys [build-info] :as state} mode config]

  (util/with-logged-time [state {:type :npm-flush}]

    (let [env-file
          (data/output-file state "cljs_env.js")

          env-content
          (js-module-env state config)

          env-modified?
          (or (not (.exists env-file))
              (not= env-content (slurp env-file)))]

      (when env-modified?
        (io/make-parents env-file)
        (spit env-file env-content))

      (doseq [src-id (:build-sources state)
              :when (not= src-id goog-base-id)
              :let [src (get-in state [:sources src-id])]
              :when (not (util/foreign? src))]

        (let [{:keys [resource-name output-name last-modified]}
              src

              {:keys [js] :as output}
              (data/get-output! state src)

              target
              (data/output-file state output-name)]

          ;; flush everything if env was modified, otherwise only flush modified
          (when (or env-modified?
                    (contains? (:compiled build-info) resource-name)
                    (not (.exists target))
                    (>= last-modified (.lastModified target)))

            (let [prepend
                  (js-module-src-prepend state src true)

                  content
                  (str prepend
                       js
                       (js-module-src-append state src)
                       (generate-source-map state output src target prepend))]

              (spit target content)
              ))))))
  state)

