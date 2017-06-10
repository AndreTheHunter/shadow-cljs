(ns shadow.cljs.devtools.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cljs-specs]
            [shadow.cljs.closure :as closure]
            [clojure.string :as str])
  (:import (java.io StringReader BufferedReader)))


(defn enhance-warnings
  "adds source excerpts to warnings if line information is available"
  [state {:keys [input file name warnings] :as rc}]
  (if-not (seq warnings)
    []
    (let [source-lines
          (into [] (-> @input
                       (StringReader.)
                       (BufferedReader.)
                       (line-seq)))

          excerpt-offset
          5

          max-lines
          (count source-lines)

          make-source-excerpt
          (fn [line col]
            (let [before
                  (Math/max 0 (- line excerpt-offset))

                  idx
                  (Math/max 0 (dec line))

                  after
                  (Math/min max-lines (+ line excerpt-offset))]

              {:start-idx before
               :before (subvec source-lines before idx)
               :line (nth source-lines idx)
               :after (subvec source-lines line after)}

              ))]

      (->> (for [{:keys [line column] :as warning} (distinct warnings)]
             (-> warning
                 (assoc :source-name name)
                 (cond->
                   file
                   (assoc :file (.getAbsolutePath file))
                   line
                   (assoc :source-excerpt (make-source-excerpt line column)))))
           (into [])))))

(defn extract-build-info [state]
  (let [source->module
        (reduce
          (fn [index {:keys [sources name]}]
            (reduce
              (fn [index source]
                (assoc index source name))
              index
              sources))
          {}
          (:build-modules state))

        compiled-sources
        (into #{} (cljs/names-compiled-in-last-build state))

        build-sources
        (->> (:build-sources state)
             (map (fn [name]
                    (let [{:keys [type js-name] :as rc}
                          (get-in state [:sources name])]
                      {:name name
                       :js-name js-name
                       :type type
                       :module (get source->module name)
                       :warnings (enhance-warnings state rc)}
                      )))
             (into []))]

    {:sources build-sources
     :compiled compiled-sources}))

(defn- update-build-info-from-modules
  [{:keys [build-modules] :as state}]
  (update state ::build-info merge {:modules build-modules}))

(defn- update-build-info-after-compile
  [state]
  (update state ::build-info merge (extract-build-info state)))

(defn process-stage
  [{::keys [config mode target-fn] :as state} stage optional?]
  (let [before
        (-> state
            (assoc ::stage stage)
            (assoc-in [::build-info stage :enter] (System/currentTimeMillis)))

        after
        (target-fn before)]
    (if (and (not optional?) (identical? before after))
      (throw (ex-info "process didn't do anything on non-optional stage"
               {:stage stage :mode mode :config config}))
      (assoc-in after [::build-info stage :exit] (System/currentTimeMillis)))))

(defn deep-merge [a b]
  (cond
    (nil? a)
    b

    (nil? b)
    a

    (and (map? a) (map? b))
    (merge-with deep-merge a b)

    (and (vector? a) (vector? b))
    (->> (concat a b)
         (distinct)
         (into []))

    (string? b)
    b

    (number? b)
    b

    (boolean? b)
    b

    :else
    (throw (ex-info "failed to merge config value" {:a a :b b}))
    ))

(defn config-merge [config mode]
  (let [mode-opts (get config mode)]
    (-> config
        (cond->
          mode-opts
          (deep-merge mode-opts))
        (dissoc :release :dev))))

(defn get-target-fn [target build-id]

  (let [target-fn-sym
        (cond
          (qualified-symbol? target)
          target

          (simple-symbol? target)
          (symbol (str target) "process")

          (keyword? target)
          (symbol (str "shadow.cljs.devtools.targets." (name target)) "process")

          :else
          (throw (ex-info (format "invalid target: %s" (pr-str target)) {:target target})))

        target-ns (-> target-fn-sym namespace symbol)]

    (when-not (find-ns target-ns)
      (try
        (require target-ns)
        (catch Exception e
          (throw (ex-info (format "failed to require :target %s for build %s" target build-id)
                   {:tag ::get-target-fn :target target} e)))))

    (let [fn (ns-resolve target-ns target-fn-sym)]
      (when-not fn
        (throw (ex-info (str "target-fn " target-fn-sym " not found") {:target target})))

      fn
      )))

(defn init
  ([mode config]
   (init (cljs/init-state) mode config))
  ([{:keys [work-dir] :as init-state} mode {:keys [id target] :as config}]
   {:pre [(cljs/compiler-state? init-state)
          (map? config)
          (keyword? mode)
          (keyword? id)
          (some? target)]
    :post [(cljs/compiler-state? %)]}

   (let [{:keys [build-options closure-defines compiler-options] :as config}
         (config-merge config mode)

         target-fn
         (get-target-fn target id)]

     ;; must do this after calling get-target-fn
     ;; the namespace that is in may have added to the multi-spec
     (when-not (s/valid? ::config/build+target config)
       (throw (ex-info "invalid build config" (assoc (s/explain-data ::config/build+target config)
                                                :tag ::config
                                                :config config))))

     (-> init-state
         (assoc :cache-dir (io/file work-dir "shadow-cljs" (name id) (name mode))
           ::stage :init
           ::config config
           ::target-fn target-fn
           ::mode mode)
         ;; FIXME: not setting this for :release builds may cause errors
         ;; http://dev.clojure.org/jira/browse/CLJS-2002
         (update :runtime assoc :print-fn :console)
         (cond->
           ;; generic dev mode, each target can overwrite in :init stage
           (= :dev mode)
           (-> (cljs/enable-source-maps)
               (cljs/merge-build-options
                 {:use-file-min false
                  :closure-defines {"goog.DEBUG" true}})
               (cljs/merge-compiler-options
                 {:optimizations :none}))

           ;; generic release mode
           (= :release mode)
           (-> (cljs/merge-compiler-options
                 {:optimizations :advanced
                  :elide-asserts true
                  :pretty-print false}))

           closure-defines
           (cljs/merge-build-options {:closure-defines closure-defines})

           compiler-options
           (cljs/merge-compiler-options compiler-options)

           ;; FIXME: CAREFUL WITH THESE, may destroy everything
           ;; run them through some kind of spec at least
           build-options
           (cljs/merge-build-options build-options))

         ;; should do all configuration necessary
         (process-stage :configure false)
         (cljs/find-resources-in-classpath)

         ;; can do things that require knowledge of the classpath
         (process-stage :init true)
         ))))


(defn compile
  [{::keys [mode] :as state}]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}

  (-> state
      (assoc ::build-info {})
      (process-stage :compile-prepare true)
      (cljs/prepare-compile)
      (cljs/prepare-modules)
      (update-build-info-from-modules)
      (cljs/compile-modules*)
      (update-build-info-after-compile)
      (process-stage :compile-finish true)))

(defn optimize
  [{::keys [mode skip-optimize] :as state}]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}
  (-> state
      (cond->
        (and (= :release mode) (not skip-optimize))
        (-> (process-stage :optimize-prepare true)
            (closure/optimize)
            (process-stage :optimize-finish true)))))

(defn check
  [state]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}
  (-> state
      (process-stage :check-prepare true)
      (closure/check)
      (process-stage :check-finish true)))

(defn flush
  [state]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}
  (process-stage state :flush true))


