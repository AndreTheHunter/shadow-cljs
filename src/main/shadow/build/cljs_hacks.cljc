(ns shadow.build.cljs-hacks
  (:require [cljs.analyzer]
            [cljs.core]))

;; these things to some slight modifications to cljs.analyzer
;; there are some odd checks related to JS integration
;; which sort of conflict with the way shadow-cljs handles this

;; it operates without the modifications but has some weird edge cases


;; it also fully replaces the :infer-externs implementation
;; the default implementation was far more ambitious by trying to keep everything typed
;; which only works reliably if everything is annotated properly
;; new users are unlikely to do that
;; basically all of cljs.core is untyped as well which means the typing is not as useful anyways

;; this impl just records all js globals that were accessed in a given namespace
;; as well as all properties identified on js objects
;; (:require ["something" :as x :refer (y)])
;; both x and y are tagged as 'js and will be recorded


;; to be fair I could have built the simplified version on top of the typed version
;; but there were I a few aspects I didn't quite understand
;; so this was easier for ME, not better.

(in-ns 'cljs.analyzer)

(def conj-to-set (fnil conj #{}))

(defn shadow-js-access-global [current-ns global]
  {:pre [(symbol? current-ns)
         (string? global)]}
  (swap! env/*compiler* update-in
    [::namespaces current-ns :shadow/js-access-global] conj-to-set global))

(defn shadow-js-access-property [current-ns prop]
  {:pre [(symbol? current-ns)
         (string? prop)]}
  (when-not (string/starts-with? prop "cljs$")
    (swap! env/*compiler* update-in
      [::namespaces current-ns :shadow/js-access-properties] conj-to-set prop)))

(defn resolve-js-var [ns sym current-ns]
  ;; quick hack to record all accesses to any JS mod
  ;; (:require ["react" :as r :refer (foo]) (r/bar ...)
  ;; will record foo+bar
  (let [prop (name sym)
        qname (symbol "js" (str ns "." prop))]
    (shadow-js-access-property current-ns prop)

    {:name qname
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

;; there is one bad call in cljs.analyzer/resolve-var
;; which calls this with a string checking the UNRESOLVED alias
;; (:require [something :as s])
;; (s/foo)
;; calls (js-module-exists? "s") which seems like a bug
;; FIXME: report JIRA issue
(defn js-module-exists? [module]
  {:pre [(symbol? module)]}
  (some? (get-in @env/*compiler* [:js-module-index (name module)])))

(defn resolve-cljs-var [ns sym current-ns]
  (merge (gets @env/*compiler* ::namespaces ns :defs sym)
         {:name (symbol (str ns) (str sym))
          :ns ns}))

(defn resolve-ns-var [ns sym current-ns]
  (cond
    (js-module-exists? ns)
    (resolve-js-var ns sym current-ns)

    (contains? (:goog-names @env/*compiler*) ns)
    {:name (symbol (str ns) (str sym))
     :ns ns}

    :else
    (resolve-cljs-var ns sym current-ns)
    ))

(defn invokeable-ns?
  "Returns true if ns is a required namespace and a JavaScript module that
   might be invokeable as a function."
  [alias env]
  (when-let [ns (resolve-ns-alias env alias nil)]
    (js-module-exists? ns)))

(defn resolve-invokeable-ns [alias current-ns env]
  (let [ns (resolve-ns-alias env alias)]
    {:name ns
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

(def known-safe-js-globals
  "symbols known to be closureJS compliant namespaces"
  #{"cljs"
    "goog"})

(defn resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  ([env sym] (resolve-var env sym nil))
  ([env sym confirm]
   (let [locals (:locals env)
         current-ns (-> env :ns :name)
         sym-ns-str (namespace sym)]
     (if (= "js" sym-ns-str)
       (do (when (contains? locals (-> sym name symbol))
             (warning :js-shadowed-by-local env {:name sym}))
           ;; always record all fully qualified js/foo.bar calls
           (let [[global & props]
                 (clojure.string/split (name sym) #"\.")]

             ;; do not record access to
             ;; js/goog.string.format
             ;; js/cljs.core.assoc
             ;; just in case someone does that, we won't need externs for those
             (when-not (contains? known-safe-js-globals global)
               (shadow-js-access-global current-ns global)
               (when (seq props)
                 (doseq [prop props]
                   (shadow-js-access-property current-ns prop)))))

           {:name sym
            :ns 'js
            :tag 'js
            :ret-tag 'js})

       (let [s (str sym)
             lb (get locals sym)
             current-ns-info (gets @env/*compiler* ::namespaces current-ns)]

         (cond
           (some? lb) lb

           (some? sym-ns-str)
           (let [ns sym-ns-str
                 ns (symbol (if (= "clojure.core" ns) "cljs.core" ns))
                 ;; thheller: remove the or
                 full-ns (resolve-ns-alias env ns (symbol ns))
                 ;; strip ns
                 sym (symbol (name sym))]
             (when (some? confirm)
               (when (not= current-ns full-ns)
                 (confirm-ns env full-ns))
               (confirm env full-ns sym))
             (resolve-ns-var full-ns sym current-ns))

           ;; FIXME: would this not be better handled if checked before calling resolve-var
           ;; and analyzing this properly?
           (dotted-symbol? sym)
           (let [idx (.indexOf s ".")
                 prefix (symbol (subs s 0 idx))
                 suffix (subs s (inc idx))]
             (if-some [lb (get locals prefix)]
               {:name (symbol (str (:name lb)) suffix)}
               (if-some [full-ns (gets current-ns-info :imports prefix)]
                 {:name (symbol (str full-ns) suffix)}
                 (if-some [info (gets current-ns-info :defs prefix)]
                   (merge info
                          {:name (symbol (str current-ns) (str sym))
                           :ns current-ns})
                   (merge (gets @env/*compiler* ::namespaces prefix :defs (symbol suffix))
                          {:name (if (= "" prefix) (symbol suffix) (symbol (str prefix) suffix))
                           :ns prefix})))))

           (some? (gets current-ns-info :uses sym))
           (let [full-ns (gets current-ns-info :uses sym)]
             (resolve-ns-var full-ns sym current-ns))

           (some? (gets current-ns-info :renames sym))
           (let [qualified-symbol (gets current-ns-info :renames sym)
                 full-ns (symbol (namespace qualified-symbol))
                 sym (symbol (name qualified-symbol))]
             (resolve-ns-var full-ns sym current-ns))

           (some? (gets current-ns-info :imports sym))
           (recur env (gets current-ns-info :imports sym) confirm)

           (some? (gets current-ns-info :defs sym))
           (do
             (when (some? confirm)
               (confirm env current-ns sym))
             (resolve-cljs-var current-ns sym current-ns))

           ;; https://dev.clojure.org/jira/browse/CLJS-2380
           ;; not sure if correct fix
           ;; cljs.core/Object is used by parse-type so using that here
           (= 'Object sym)
           '{:name cljs.core/Object
             :ns cljs.core}

           (core-name? env sym)
           (do
             (when (some? confirm)
               (confirm env 'cljs.core sym))
             (resolve-cljs-var 'cljs.core sym current-ns))

           (invokeable-ns? s env)
           (resolve-invokeable-ns s current-ns env)

           :else
           (do (when (some? confirm)
                 (confirm env current-ns sym))
               (resolve-cljs-var current-ns sym current-ns)
               )))))))

(defn infer-externs-dot [{:keys [form target-tag method field env prop tag] :as ast}]
  (let [sprop (str prop)]

    ;; simplified *warn-on-infer* warnings since we do not care about them being typed
    ;; we just need ^js not ^js/Foo.Bar
    (when (and (or (nil? tag) (= 'any tag))
               (not= target-tag 'clj)
               (not= "-prototype" sprop)
               (not= "-constructor" sprop)
               ;; defrecord
               (not= "-getBasis" sprop)
               (not (string/starts-with? sprop "-cljs$"))
               ;; protocol fns, never need externs for those
               (not (string/includes? sprop "$arity$"))
               ;; set in cljs.core/extend-prefix hack below
               (not (some-> prop meta :shadow/protocol-prop)))

      (warning :infer-warning env {:warn-type :target :form form}))

    (when (js-tag? tag)
      (shadow-js-access-property
        (-> env :ns :name)
        (str (or method field))
        ))))

(defn analyze-dot [env target member member+ form]
  (when (nil? target)
    (throw (ex-info "Cannot use dot form on nil" {:form form})))

  (let [member-sym? (symbol? member)
        member-seq? (seq? member)
        prop-access? (and member-sym? (= \- (first (name member))))

        ;; common for all paths
        enve (assoc env :context :expr)
        targetexpr (analyze enve target)
        form-meta (meta form)
        target-tag (:tag targetexpr)
        tag (or (:tag form-meta)
                (and (js-tag? target-tag) 'js)
                nil)

        ast
        {:op :dot
         :env env
         :form form
         :target targetexpr
         :target-tag target-tag
         :tag tag
         :prop member}

        ast
        (cond
          ;; (. thing (foo) 1 2 3)
          (and member-seq? (seq member+))
          (throw (ex-info "dot with extra args" {:form form}))

          ;; (. thing (foo))
          ;; (. thing (foo 1 2 3))
          member-seq?
          (let [[method & args] member
                argexprs (map #(analyze enve %) args)
                children (into [targetexpr] argexprs)]
            (assoc ast :method method :args argexprs :children children))

          ;; (. thing -foo 1 2 3)
          (and prop-access? (seq member+))
          (throw (ex-info "dot prop access with args" {:form form}))

          ;; (. thing -foo)
          prop-access?
          (let [children [targetexpr]]
            (assoc ast :field (-> member (name) (subs 1) (symbol)) :children children))

          ;; (. thing foo)
          ;; (. thing foo 1 2 3)
          member-sym?
          (let [argexprs (map #(analyze enve %) member+)
                children (into [targetexpr] argexprs)]
            (assoc ast :method member :args argexprs :children children))

          :else
          (throw (ex-info "invalid dot form" {:form form})))]

    (infer-externs-dot ast)

    ast))

(defmethod parse '.
  [_ env [_ target field & member+ :as form] _ _]
  (disallowing-recur (analyze-dot env target field member+ form)))

;; thheller: changed tag inference to always use tag on form first
;; destructured bindings had meta in their :init
;; https://dev.clojure.org/jira/browse/CLJS-2385
(defn get-tag [e]
  (if-some [tag (-> e :form meta :tag)]
    tag
    (if-some [tag (-> e :tag)]
      tag
      (-> e :info :tag)
      )))

;; cljs.analyzer/parse-type, cleaned up since I couldnt follow it otherwise
;; removed one resolve-var call
;; added :tag
(defn parse-type
  [op env [_ tsym fields pmasks body :as form]]
  (let [ns
        (-> env :ns :name)

        tsym-meta
        (meta tsym)

        ;; thheller: I don't understand why this uses resolve-var only to get the name?
        type-sym
        (with-meta
          (symbol (str ns) (str tsym))
          tsym-meta)

        locals-fields
        (if (= :defrecord* op)
          (concat fields '[__meta __extmap ^:mutable __hash])
          fields)

        locals
        (reduce
          (fn [m fld]
            (let [field-info
                  {:name fld
                   :line (get-line fld env)
                   :column (get-col fld env)
                   :field true
                   :mutable (-> fld meta :mutable)
                   :unsynchronized-mutable (-> fld meta :unsynchronized-mutable)
                   :volatile-mutable (-> fld meta :volatile-mutable)
                   :tag (-> fld meta :tag)
                   :shadow (get m fld)}]
              (assoc m fld field-info)))
          {} ;; FIXME: should this use env :locals?
          locals-fields)

        protocols
        (-> tsym meta :protocols)]

    (swap! env/*compiler* update-in [::namespaces ns :defs tsym]
      (fn [m]
        (-> (assoc m
                   :name type-sym
                   :type true
                   :tag type-sym
                   :num-fields (count fields)
                   :record (= :defrecord* op))
            (merge (source-info tsym env)))))

    {:op op :env env :form form :t type-sym :fields fields :pmasks pmasks
     :tag type-sym
     :protocols (disj protocols 'cljs.core/Object)
     :body (analyze (assoc env :locals locals) body)}))

(in-ns 'cljs.core)

;; https://dev.clojure.org/jira/browse/CLJS-1439

(core/defmacro goog-define
  "Defines a var using `goog.define`. Passed default value must be
  string, number or boolean.

  Default value can be overridden at compile time using the
  compiler option `:closure-defines`.

  Example:
    (ns your-app.core)
    (goog-define DEBUG! false)
    ;; can be overridden with
    :closure-defines {\"your_app.core.DEBUG_BANG_\" true}
    or
    :closure-defines {'your-app.core/DEBUG! true}"
  [sym default]
  (assert-args goog-define
    (core/or (core/string? default)
             (core/number? default)
             (core/true? default)
             (core/false? default)) "a string, number or boolean as default value")
  (core/let [defname (comp/munge (core/str *ns* "/" sym))
             type (core/cond
                    (core/string? default) "string"
                    (core/number? default) "number"
                    (core/or (core/true? default) (core/false? default)) "boolean")]
    `(do
       (declare ~(core/vary-meta sym
                   (fn [m]
                     (core/cond-> m
                       (core/not (core/contains? m :tag))
                       (core/assoc :tag (core/symbol type))
                       ))))
       (~'js* ~(core/str "/** @define {" type "} */"))
       (goog/define ~defname ~default))))

(defn shadow-mark-protocol-prop [sym]
  (with-meta sym {:shadow/protocol-prop true}))

;; multimethod in core, although I don't see how this is ever going to go past 2 impls?
;; also added the metadata for easier externs inference
(defn- extend-prefix [tsym sym]
  (let [prop-sym
        (-> (to-property sym)
            (cond->
              ;; exploiting a "flaw" where extend-prefix is called with a string
              ;; instead of a symbol for protocol impls
              ;; adding the extra meta so we can use it for smarter externs inference
              (core/string? sym)
              (shadow-mark-protocol-prop)
              ))]

    (core/case (core/-> tsym meta :extend)
      :instance
      `(. ~tsym ~prop-sym)
      ;; :default
      `(.. ~tsym ~'-prototype ~prop-sym))))


(core/defmacro implements?
  "EXPERIMENTAL"
  [psym x]
  (core/let [p (:name (cljs.analyzer/resolve-var (dissoc &env :locals) psym))
             prefix (protocol-prefix p)
             ;; thheller: ensure things are tagged so externs inference knows this is a protocol prop
             protocol-prop (shadow-mark-protocol-prop (symbol (core/str "-" prefix)))
             xsym (bool-expr (gensym))
             [part bit] (fast-path-protocols p)
             msym (symbol
                    (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    (core/if-not (core/symbol? x)
      `(let [~xsym ~x]
         (if ~xsym
           (if (or ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit) false)
                   (identical? cljs.core/PROTOCOL_SENTINEL (. ~xsym ~protocol-prop)))
             true
             false)
           false))
      `(if-not (nil? ~x)
         (if (or ~(if bit `(unsafe-bit-and (. ~x ~msym) ~bit) false)
                 (identical? cljs.core/PROTOCOL_SENTINEL (. ~x ~protocol-prop)))
           true
           false)
         false))))

(core/defmacro satisfies?
  "Returns true if x satisfies the protocol"
  [psym x]
  (core/let [p (:name
                 (cljs.analyzer/resolve-var
                   (dissoc &env :locals) psym))
             prefix (protocol-prefix p)
             ;; thheller: ensure things are tagged so externs inference knows this is a protocol prop
             protocol-prop (shadow-mark-protocol-prop (symbol (core/str "-" prefix)))
             xsym (bool-expr (gensym))
             [part bit] (fast-path-protocols p)
             msym (symbol
                    (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    (core/if-not (core/symbol? x)
      `(let [~xsym ~x]
         (if-not (nil? ~xsym)
           (if (or ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit) false)
                   (identical? cljs.core/PROTOCOL_SENTINEL (. ~xsym ~protocol-prop)))
             true
             (if (coercive-not (. ~xsym ~msym))
               (cljs.core/native-satisfies? ~psym ~xsym)
               false))
           (cljs.core/native-satisfies? ~psym ~xsym)))
      `(if-not (nil? ~x)
         (if (or ~(if bit `(unsafe-bit-and (. ~x ~msym) ~bit) false)
                 (identical? cljs.core/PROTOCOL_SENTINEL (. ~x ~protocol-prop)))
           true
           (if (coercive-not (. ~x ~msym))
             (cljs.core/native-satisfies? ~psym ~x)
             false))
         (cljs.core/native-satisfies? ~psym ~x)))))


(core/defn- emit-defrecord
  "Do not use this directly - use defrecord"
  [env tagname rname fields impls]
  (core/let [hinted-fields fields
             fields (vec (map #(with-meta % nil) fields))
             base-fields fields
             pr-open (core/str "#" #?(:clj  (.getNamespace rname)
                                      :cljs (namespace rname))
                               "." #?(:clj  (.getName rname)
                                      :cljs (name rname))
                               "{")
             fields (conj fields '__meta '__extmap (with-meta '__hash {:mutable true}))]
    (core/let [gs (gensym)
               ksym (gensym "k")
               impls (concat
                       impls
                       ['IRecord
                        'ICloneable
                        `(~'-clone [this#] (new ~tagname ~@fields))
                        'IHash
                        `(~'-hash [this#]
                           (caching-hash this#
                             (fn [coll#]
                               (bit-xor
                                 ~(hash (core/-> rname comp/munge core/str))
                                 (hash-unordered-coll coll#)))
                             ~'__hash))
                        'IEquiv
                        ;; thheller: added tags here
                        (core/let [this (with-meta (gensym 'this) {:tag 'clj})
                                   other (with-meta (gensym 'other) {:tag 'clj})]
                          `(~'-equiv [~this ~other]
                             (and (some? ~other)
                                  (identical? (.-constructor ~this)
                                    (.-constructor ~other))
                                  ~@(map (core/fn [field]
                                           `(= (.. ~this ~(to-property field))
                                               (.. ~other ~(to-property field))))
                                      base-fields)
                                  (= (.-__extmap ~this)
                                     (.-__extmap ~other)))))
                        'IMeta
                        `(~'-meta [this#] ~'__meta)
                        'IWithMeta
                        `(~'-with-meta [this# ~gs] (new ~tagname ~@(replace {'__meta gs} fields)))
                        'ILookup
                        `(~'-lookup [this# k#] (-lookup this# k# nil))
                        `(~'-lookup [this# ~ksym else#]
                           (case ~ksym
                             ~@(mapcat (core/fn [f] [(keyword f) f]) base-fields)
                             (cljs.core/get ~'__extmap ~ksym else#)))
                        'ICounted
                        `(~'-count [this#] (+ ~(count base-fields) (count ~'__extmap)))
                        'ICollection
                        `(~'-conj [this# entry#]
                           (if (vector? entry#)
                             (-assoc this# (-nth entry# 0) (-nth entry# 1))
                             (reduce -conj
                               this#
                               entry#)))
                        'IAssociative
                        `(~'-assoc [this# k# ~gs]
                           (condp keyword-identical? k#
                             ~@(mapcat (core/fn [fld]
                                         [(keyword fld) (list* `new tagname (replace {fld gs '__hash nil} fields))])
                                 base-fields)
                             (new ~tagname ~@(remove #{'__extmap '__hash} fields) (assoc ~'__extmap k# ~gs) nil)))
                        'IMap
                        `(~'-dissoc [this# k#] (if (contains? #{~@(map keyword base-fields)} k#)
                                                 (dissoc (-with-meta (into {} this#) ~'__meta) k#)
                                                 (new ~tagname ~@(remove #{'__extmap '__hash} fields)
                                                   (not-empty (dissoc ~'__extmap k#))
                                                   nil)))
                        'ISeqable
                        `(~'-seq [this#] (seq (concat [~@(map #(core/list `vector (keyword %) %) base-fields)]
                                                ~'__extmap)))

                        'IIterable
                        `(~'-iterator [~gs]
                           (RecordIter. 0 ~gs ~(count base-fields) [~@(map keyword base-fields)] (if ~'__extmap
                                                                                                   (-iterator ~'__extmap)
                                                                                                   (core/nil-iter))))

                        'IPrintWithWriter
                        `(~'-pr-writer [this# writer# opts#]
                           (let [pr-pair# (fn [keyval#] (pr-sequential-writer writer# pr-writer "" " " "" opts# keyval#))]
                             (pr-sequential-writer
                               writer# pr-pair# ~pr-open ", " "}" opts#
                               (concat [~@(map #(core/list `vector (keyword %) %) base-fields)]
                                 ~'__extmap))))
                        ])
               [fpps pmasks] (prepare-protocol-masks env impls)
               protocols (collect-protocols impls env)
               tagname (vary-meta tagname assoc
                         :protocols protocols
                         :skip-protocol-flag fpps)]
      `(do
         (~'defrecord* ~tagname ~hinted-fields ~pmasks
           (extend-type ~tagname ~@(dt->et tagname impls fields true)))))))
