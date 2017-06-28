(ns shadow.cljs.npm.util
  (:require ["fs" :as fs]))

(defn slurp [file]
  (-> (fs/readFileSync file)
      (.toString)))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))
