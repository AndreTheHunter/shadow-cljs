(ns demo.test-dummy
  (:require [cljs.test :as ct :refer (deftest is)]
            ["react" :as react]))

(ct/use-fixtures :once
  {:before
   (fn []
     (println "once before"))
   :after
   (fn []
     (println "once after"))})

(ct/use-fixtures :each
  {:before
   (fn []
     (println "each before"))

   :after
   (fn []
     (println "each after"))})

(deftest a-failing-test
  (is (= 1 2)))

(deftest a-passing-test
  (is (= 1 1)))
