(ns shadow.cljs.ns-form
  "ns parser based on spec"
  (:require [clojure.spec.alpha :as s]
            [clojure.core.specs.alpha :as cs]
            [clojure.pprint :refer (pprint)]
            [cljs.compiler :as cljs-comp]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

;; didnt use most of ::cs since the CLJS ns form is quite different

;; some.ns or "npm" package
(s/def ::lib
  #(or (simple-symbol? %)
       (string? %)))

(s/def ::syms
  (s/coll-of simple-symbol?))

;; kw-args
(s/def ::exclude
  (s/cat
    :key #{:exclude}
    :value ::syms))

(s/def ::as
  (s/cat
    :key #{:as}
    :value ::cs/local-name))

(s/def ::refer
  (s/cat
    :key #{:refer :refer-macros}
    :value ::syms))

(s/def ::only
  (s/cat
    :key #{:only}
    :value ::syms))

(s/def ::include-macros
  (s/cat
    :key #{:include-macros}
    :value boolean?))

(s/def ::rename
  (s/cat
    :key #{:rename}
    :value (s/map-of simple-symbol? simple-symbol?)))

(defn check [spec form]
  (s/explain spec form)
  (pprint (s/conform spec form)))

;; (check ::as '[:as foo])

;; :require

(s/def ::require-opt
  (s/alt
    :as ::as
    :refer ::refer
    :refer-macros ::refer
    :rename ::rename
    :include-macros ::include-macros
    ))

(s/def ::require
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib ::lib
      :opts (s/* ::require-opt))))

(s/def ::ns-require
  (s/spec
    (s/cat
      :clause
      #{:require :require-macros}
      :requires
      (s/+ ::require)
      :flags
      (s/* #{:reload :reload-all})
      )))

(comment
  (check ::ns-require
    '(:require
       just.a.sym
       [goog.string :as gstr]
       [some.foo :as foo :refer (x y z) :refer-macros (foo bar) :rename {x c}]
       ["react" :as react]
       :reload)))

;; :import

(s/def ::import
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib ::lib
      :names (s/+ simple-symbol?))))

(s/def ::ns-import
  (s/spec
    (s/cat
      :clause
      #{:import :import-macros}
      :imports
      (s/+ ::import))))

(comment
  (check ::ns-import
    '(:import
       that.Class
       [another Foo Bar]
       [just.Single]
       )))

;; :refer-clojure
(s/def ::refer-clojure-opt
  (s/alt
    :exclude ::exclude
    :rename ::rename
    ))

(s/def ::ns-refer-clojure
  (s/spec
    (s/cat
      :clause
      #{:refer-clojure}
      :opts
      (s/+ ::refer-clojure-opt))))

(comment
  (check ::ns-refer-clojure
    '(:refer-clojure
       :exclude (assoc)
       :rename {conj jnoc})))

(s/def ::use-macro
  (s/spec
    (s/cat
      :ns
      simple-symbol?
      :only
      #{:only}
      :syms
      ::syms)))

(s/def ::ns-use-macros
  (s/spec
    (s/cat
      :clause
      #{:use-macros}
      :uses
      (s/+ ::use-macro))))

(comment
  (check ::ns-use-macros
    '(:use-macros [macro-use :only (that-one)])))

(s/def ::use-opt
  (s/alt
    :only ::only
    :rename ::rename
    ))

(s/def ::use
  (s/spec
    (s/cat
      :lib
      ::lib
      :opts
      (s/+ ::use-opt))))

(s/def ::ns-use
  (s/spec
    (s/cat
      :clause
      #{:use}
      :uses
      (s/+ ::use))))

(comment
  (check ::ns-use
    '(:use [something.fancy :only [everything] :rename {everything nothing}])))

;; :ns

(s/def ::ns-clauses
  (s/*
    (s/alt
      :refer-clojure ::ns-refer-clojure
      :require ::ns-require
      :import ::ns-import
      :use-macros ::ns-use-macros
      :use ::ns-use)))

(s/def ::ns-form
  (s/cat
    :ns '#{ns}
    :name simple-symbol?
    :docstring (s/? string?)
    :meta (s/? map?)
    :clauses ::ns-clauses))

(defmulti reduce-ns-clause (fn [ns-info [key clause]] key))

(defn make-npm-alias [lib]
  (when (str/starts-with? lib ".")
    (throw (ex-info "relative npm imports not supported yet" {:lib lib})))

  (symbol (str "npm$import." (cljs-comp/munge lib))))

(defn maybe-npm [lib]
  (if (string? lib)
    [(make-npm-alias lib) true]
    [lib false]))

(defn opts->map [opts]
  (reduce
    (fn [m [key opt :as x]]
      (let [{:keys [key value]} opt]
        (when (contains? m key)
          (throw (ex-info "duplicate opt key" {:opt opt :m m})))
        (assoc m key value)))
    {}
    opts))

(defn merge-require-fn [merge-key ns]
  ;; FIXME: ensure unique
  (fn [ns-info sym]
    (update ns-info merge-key assoc sym ns)))

(defn merge-rename-fn [merge-key ns]
  (fn [ns-info rename-to rename-from]
    (update ns-info merge-key assoc rename-from (symbol (str ns) (str rename-to)))))

(defn reduce-require [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (update :requires assoc require require)
        (update :deps conj require))
    :seq
    (let [{:keys [lib opts]}
          require

          {:keys [as refer refer-macros include-macros rename] :as opts-m}
          (opts->map opts)

          [ns js?]
          (maybe-npm lib)

          refer
          (if (seq rename)
            (remove rename refer)
            refer)]

      (-> ns-info
          (update :deps conj ns)
          (update :requires assoc ns ns)
          (cond->
            as
            (update :requires assoc as ns)

            js?
            (update :js-requires conj lib)

            (or include-macros (seq refer-macros))
            (-> (update :require-macros assoc ns ns)
                (cond->
                  as
                  (update :require-macros assoc as ns))))
          (reduce->
            (merge-require-fn :uses ns)
            refer)
          (reduce->
            (merge-require-fn :use-macros ns)
            refer-macros)
          (reduce-kv->
            (fn [ns-info rename-to rename-from]
              (update ns-info :renames assoc rename-from (symbol (str ns) (str rename-to))))
            rename)))))

(defn reduce-require-macros [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (update :require-macros assoc require require))

    :seq
    (let [{ns :lib opts :opts}
          require

          {:keys [as refer rename] :as opts-m}
          (opts->map opts)

          refer
          (if (seq rename)
            (remove rename refer)
            refer)]

      (when (string? ns)
        (throw (ex-info "require-macros only works with symbols not strings" {:require require :ns-info ns-info})))

      (-> ns-info
          (update :require-macros assoc ns ns)
          (cond->
            as
            (update :require-macros assoc as ns))
          (reduce->
            (merge-require-fn :use-macros ns)
            refer)
          (reduce-kv->
            (merge-rename-fn :rename-macros ns)
            rename)))))

(defmethod reduce-ns-clause :require [ns-info [_ clause]]
  (let [{:keys [clause requires flags]} clause]
    (-> ns-info
        (update :seen conj clause)
        (update :flags assoc clause (into #{} flags))
        (cond->
          (= :require clause)
          (reduce-> reduce-require requires)
          (= :require-macros clause)
          (reduce-> reduce-require-macros requires)
          ))))

(defn reduce-import [ns-info [key import]]
  (case key
    :sym ;; a.fully-qualified.Name, never a string
    (let [class (-> import str (str/split #"\.") last symbol)]
      (-> ns-info
          (update :requires assoc class import)
          (update :imports assoc class import)
          (update :deps conj import)))

    :seq
    (let [{:keys [lib names]} import]
      ;; (:import [goog.foo.Class]) is a no-op since no names are mentioned
      ;; FIXME: worthy of a warning?

      (if-not (seq names)
        ns-info
        (let [[ns js?] (maybe-npm lib)]
          (-> ns-info
              (cond->
                js?
                (update :js-requires conj lib))

              (reduce->
                (fn [ns-info sym]
                  (let [fqn (symbol (str ns "." sym))]
                    (-> ns-info
                        (update :imports assoc sym fqn)
                        (update :requires assoc sym fqn)
                        (update :deps conj fqn))))
                names)
              ))))))

(defmethod reduce-ns-clause :import [ns-info [_ clause]]
  (let [{:keys [imports]} clause]
    (reduce reduce-import ns-info imports)))

(defmethod reduce-ns-clause :refer-clojure [ns-info [_ clause]]
  (let [{:keys [exclude rename] :as opts}
        (opts->map (:opts clause))]

    (-> ns-info
        (update :excludes set/union (set exclude))
        (reduce-kv->
          (merge-rename-fn :renames 'cljs.core)
          rename))))

(defmethod reduce-ns-clause :use [ns-info [_ clause]]
  (let [{:keys [uses]} clause]
    (reduce
      (fn [ns-info {:keys [lib opts] :as use}]
        (let [{:keys [only rename] :as opts}
              (opts->map opts)

              only
              (if (seq rename)
                (remove rename only)
                only)

              [ns js?]
              (maybe-npm lib)]

          (-> ns-info
              (cond->
                js?
                (update :js-requires conj lib))
              (update :requires assoc ns ns)
              (update :deps conj ns)
              (reduce->
                (merge-require-fn :uses ns)
                only)
              (reduce-kv->
                (merge-rename-fn :renames ns)
                rename
                ))))
      ns-info
      uses)))

(defmethod reduce-ns-clause :use-macros [ns-info [_ clause]]
  (let [{:keys [uses]} clause]
    (reduce
      (fn [ns-info {:keys [ns only syms] :as use}]
        (-> ns-info
            (update :require-macros assoc ns ns)
            (reduce->
              (merge-require-fn :use-macros ns)
              syms)))
      ns-info
      uses)))

(defn parse [form]
  (let [conformed
        (s/conform ::ns-form form)]

    (when (= conformed ::s/invalid)
      (s/explain ::ns-form form)
      (throw (ex-info "failed to parse ns form"
               (assoc (s/explain-data ::ns-form form)
                      :tag ::error
                      :input form))))

    (let [{:keys [name docstring meta clauses] :or {meta {}}}
          conformed

          meta
          (cond-> meta
            docstring
            (update :doc str docstring))

          ns-info
          {:excludes #{}
           :seen #{}
           :name (with-meta name meta)
           :meta meta
           :js-requires #{}
           :imports nil ;; {Class ns}
           :requires '{cljs.core cljs.core}
           :require-macros '{cljs.core cljs.core}
           :deps []
           :uses nil
           :use-macros nil
           :renames nil
           :rename-macros nil}

          {:keys [deps] :as ns-info}
          (reduce reduce-ns-clause ns-info clauses)

          deps
          (->> deps
               (distinct)
               (into []))]

      ;; FIXME: shadow.cljs uses :require-order since that was there before :deps
      ;; should probably rename all references of :deps to :deps to match cljs
      ;; for now just copy
      (assoc ns-info :deps deps :require-order deps)
      )))