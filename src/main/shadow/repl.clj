(ns shadow.repl
  (:refer-clojure :exclude (add-watch remove-watch))
  (:require [shadow.repl.protocols :as p]))

(defonce ^:private root-id-ref
  (volatile! 0))

(defonce ^:private callbacks-ref
  (volatile! {}))

;; private but called from macro so can't be private?
(defn next-root-id []
  (vswap! root-id-ref inc))

(def ^:dynamic *root-id* 0)
(def ^:dynamic *level-id* 0)

(defonce roots-ref
  (volatile! {*root-id* {::root-id 0 ::levels []}}))

(defn level-enter [level]
  ;; FIXME: not thread-safe
  ;; calls deref on roots-ref and then later calls swap assuming nothing has changed
  ;; not sure that matters though as every thing is assumed to block anyways
  (let [level-id
        (-> @roots-ref (get *root-id*) ::levels count)

        level
        (assoc level
          ::root-id *root-id*
          ::level-id level-id)

        level
        (reduce-kv
          (fn [level callback-id level-callback]
            (p/will-enter-level level-callback level))
          level
          @callbacks-ref)]

    (vswap! roots-ref update-in [*root-id* ::levels] conj level)

    level))

(defn level-exit [level]
  (vswap! roots-ref update-in [*root-id* ::levels] pop)

  (doseq [[id level-callback] @callbacks-ref]
    (p/did-exit-level level-callback level)))

;; PUBLIC API

(defn add-watch [watch-id level-callback]
  {:pre [(satisfies? p/ILevelCallback level-callback)]}
  (vswap! callbacks-ref assoc watch-id level-callback)
  watch-id)

(defn remove-watch [watch-id]
  (vswap! callbacks-ref dissoc watch-id))

(defmacro takeover [level & body]
  `(let [level# (level-enter ~level)]
     (try
       (binding [*level-id* (get level# ::level-id)]
         ~@body)
       (finally
         (level-exit level#)
         ))))

(defmacro enter-root [root-info & body]
  {:pre [(map? root-info)]}
  `(let [root-id# (next-root-id)]
     (binding [*root-id* root-id#]
       (vswap! roots-ref assoc *root-id* (assoc ~root-info ::root-id root-id# ::levels []))
       (try
         ~@body

         (finally
           (vswap! roots-ref dissoc *root-id*))))))

(defn roots []
  @roots-ref)

(defn root
  ([]
    (root *root-id*))
  ([root-id]
   (get @roots-ref root-id)))

(defn level
  ([]
    (level *root-id* *level-id*))
  ([level-id]
    (level *root-id* level-id))
  ([root-id level-id]
   (get-in @roots-ref [root-id ::levels level-id])
    ))

(defn self []
  (level))

(defn id []
  [*root-id* *level-id*])
