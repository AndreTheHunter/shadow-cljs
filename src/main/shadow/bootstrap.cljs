(ns shadow.bootstrap
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [clojure.set :as set]
            [cljs.core.async :as async]
            [cljs.js :as cljs]
            [shadow.xhr :as xhr]
            [shadow.js]
            [cognitect.transit :as transit]
            [cljs.env :as env]
            [goog.async.run])
  (:import [goog.net BulkLoader]))

(goog-define asset-path "/js/bootstrap")

(defn compile-state-ref? [x]
  (and (instance? cljs.core/Atom x) (map? @x)))

(defn transit-read [txt]
  (let [r (transit/reader :json)]
    (transit/read r txt)))

(defn transit-load [path]
  (xhr/chan :GET path nil {:body-only true
                           :transform transit-read}))

(defonce loaded-ref (atom #{}))

;; calls to this will be injected by shadow-cljs
;; it will receive an array of strings matching the goog.provide
;; names that where provided by the "app"
(defn set-loaded [namespaces]
  (let [loaded (into #{} (map symbol) namespaces)]
    (swap! loaded-ref set/union loaded)))

(defonce index-ref (atom nil))

(defn build-index [sources]
  (let [idx
        (reduce
          (fn [idx {:keys [resource-id] :as rc}]
            (assoc-in idx [:sources resource-id] rc))
          {:sources-ordered sources}
          sources)

        idx
        (reduce
          (fn [idx [provide resource-id]]
            (assoc-in idx [:sym->id provide] resource-id))
          idx
          (for [{:keys [resource-id provides]} sources
                provide provides]
            [provide resource-id]))]

    (reset! index-ref idx)

    (js/console.log "build-index" idx)

    idx))

(defn get-ns-info [ns]
  (let [idx @index-ref
        id (get-in idx [:sym->id ns])]
    (or (get-in idx [:sources id])
        (throw (ex-info (str "ns " ns " not available") {:ns ns}))
        )))

(defn find-deps [entries]
  {:pre [(set? entries)
         (every? symbol? entries)]}
  ;; abusing that :sources-ordered is in correct dependency order
  ;; just walk in reverse and pick up everything along the way
  (->> (reverse (:sources-ordered @index-ref))
       (reduce
         (fn [{:keys [deps order] :as x} {:keys [resource-id output-name provides requires] :as src}]

           (cond
             ;; don't load files that don't provide anything we want
             (not (seq (set/intersection deps provides)))
             x

             :else
             {:deps (set/union deps requires)
              :order (conj order src)}))
         {:deps entries
          :order []})
       (:order)
       (reverse)
       (into [])))

(defn execute-load! [compile-state-ref {:keys [type text uri ns provides] :as load-info}]
  (js/console.log "load" type ns load-info)
  (case type
    :analyzer
    (let [data (transit-read text)]
      (cljs/load-analysis-cache! compile-state-ref ns data))
    :js
    (do (swap! loaded-ref set/union provides)
        (swap! cljs/*loaded* set/union provides)
        (js/eval text))))

(defn queue-task! [task]
  ;; FIXME: this is a very naive queue that does all pending tasks at once
  ;; should use something like window.requestIdleCallback that does as much work as
  ;; possible in the time it was given and then yield control back to the browser
  (js/goog.async.run task))

(defn load-namespaces
  "loads a set of namespaces, must be called after init"
  [compile-state-ref namespaces cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (set? namespaces)
         (every? symbol? namespaces)
         (fn? cb)]}
  (let [deps-to-load-for-ns
        (find-deps namespaces)

        macro-deps
        (->> deps-to-load-for-ns
             (filter #(= :cljs (:type %)))
             (map :macro-requires)
             (reduce set/union)
             (map #(symbol (str % "$macros")))
             (into #{}))

        ;; second pass due to circular dependencies in macros
        deps-to-load-with-macros
        (find-deps (set/union namespaces macro-deps))

        compile-state
        @compile-state-ref

        things-already-loaded
        (->> deps-to-load-with-macros
             (filter #(set/superset? @loaded-ref (:provides %)))
             (map :provides)
             (reduce set/union))

        js-files-to-load
        (->> deps-to-load-with-macros
             (remove #(set/superset? @loaded-ref (:provides %)))
             (map (fn [{:keys [ns output-name provides]}]
                    {:type :js
                     :ns ns
                     :provides provides
                     :uri (str asset-path "/js/" output-name)})))

        analyzer-data-to-load
        (->> deps-to-load-with-macros
             (filter #(= :cljs (:type %)))
             ;; :dump-core still populates the cljs.core analyzer data with an empty map
             (filter #(nil? (get-in compile-state [:cljs.analyzer/namespaces (:ns %) :name])))
             (map (fn [{:keys [ns source-name]}]
                    {:type :analyzer
                     :ns ns
                     :uri (str asset-path "/ana/" source-name ".ana.transit.json")})))

        load-info
        (-> []
            (into js-files-to-load)
            (into analyzer-data-to-load))]

    ;; this is transfered to cljs/*loaded* here to delay it as much as possible
    ;; the JS may already be loaded but the analyzer data may be missing
    ;; this way cljs.js is forced to ask first
    (swap! cljs/*loaded* set/union things-already-loaded)

    ;; may not need to load anything sometimes?
    (if (empty? load-info)
      (cb {:lang :js :source ""})

      (let [uris
            (into [] (map :uri) load-info)

            loader
            (BulkLoader. (into-array uris))]

        (.listen loader js/goog.net.EventType.SUCCESS
          (fn [e]
            (let [texts (.getResponseTexts loader)]
              (doseq [load (map #(assoc %1 :text %2) load-info texts)]
                (queue-task! #(execute-load! compile-state-ref load)))

              (queue-task! #(js/console.log "compile-state after load" @compile-state-ref))

              ;; callback with dummy so cljs.js doesn't attempt to load deps all over again
              (queue-task! #(cb {:lang :js :source ""}))
              )))

        (.load loader)))
    ))

(defn load
  ":load fn for cljs.js, must be passed the compile-state as first arg
   eg. :load (partial boot/load compile-state-ref)"
  [compile-state-ref {:keys [name path macros] :as rc} cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (symbol? name)
         (fn? cb)]}
  (let [ns (if macros
             (symbol (str name "$macros"))
             name)]
    ;; just ensures we actually have data for it
    (get-ns-info ns)
    (load-namespaces compile-state-ref #{ns} cb)))

(defn fix-provide-conflict! []
  ;; since cljs.js unconditionally does a goog.require("cljs.core$macros")
  ;; the compile pretended to provide this but didn't
  ;; need to remove that before we load it, otherwise it would goog.provide conflict
  ;; FIXME: should test if actually empty, might delete something accidentally?
  (js-delete js/cljs "core$macros"))

(defn init
  "initializes the bootstrapped compiler by loading the dependency index
   and loading cljs.core + macros (and namespaces specified in :load-on-init)"
  [compile-state-ref {:keys [load-on-init] :as opts} init-cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (map? opts)
         (fn? init-cb)]}
  ;; FIXME: add goog-define to path
  (if @index-ref
    (init-cb)
    (do (fix-provide-conflict!)
        (go (when-some [data (<! (transit-load (str asset-path "/index.transit.json")))]
              (build-index data)
              (load-namespaces
                compile-state-ref
                (into '#{cljs.core cljs.core$macros} load-on-init)
                init-cb))))))
