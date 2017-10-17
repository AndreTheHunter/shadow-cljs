(ns demo.native
  (:require ["react" :as r :refer (thing)]
            [clojure.string :as string]
            [clojure.set :as set]))

;; lots of native interop, not actually valid code, just for testing externs generator

(thing)

(.nested (.test (r/xyz)))
(.bar r)

(defn x [^js y]
  (.. y (jsFun) -jsProp (jsFunTwo)))

(defn wrap-baz [x]
  (.baz x))

(js/foo.bar.xyz)
(js/goog.object.set nil nil)
(js/cljs.core.assoc nil :foo :bar)


(defn thing [{:keys [foo] :as this}]
  (.componentDidUpdate ^js/Thing this))

(defn thing2 [simple]
  (.componentDidUpdate ^js/Thing simple))

foo ;; warning, to prevent cache
