(ns shadow.cljs.ns-form-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.ns-form :as ns-form]
            [cljs.analyzer :as a]
            [clojure.repl :as repl]))

(def ns-env
  (assoc-in (a/empty-env) [:ns :name] 'cljs.user))

(defn cljs-parse-ns [ns-env form]
  (binding [a/*cljs-ns* 'cljs.user
            a/*analyze-deps* false
            a/*load-macros* false]
    (a/analyze ns-env form)))

(deftest test-parse-ns
  (try
    (let [test
          '(ns something
             "doc before meta"
             {:some :meta}
             (:refer-clojure :exclude (whatever) :rename {assoc cossa + plus})
             (:use-macros [macro-use :only (that-one)])
             ;; FIXME: why does cljs enforce that a :rename was :refer first? what else are you going to rename?
             (:require-macros [that.macro :as m :refer (baz) :rename {baz zab}])
             (:require
               only-symbol
               [some.ns :as alias :refer (foo x) :refer-macros (a-macro-from-some-ns) :rename {foo bar}]
               [another.ns :as x :include-macros true]
               :reload-all)
             (:use [something.fancy :only [everything] :rename {everything nothing}])
             (:import
               [goog.ui SomeElement OtherElement]
               a.fully-qualified.Name))

          a (ns-form/parse test)
          b (-> (cljs-parse-ns ns-env test)
                (dissoc :env :form))

          check
          (fn [x]
            (-> x (select-keys [:deps]) pprint))]

      (pprint test)

      (check a)
      (check b)
      ;; (pprint a)

      (is (= (:name a) (:name b)))
      ;; cljs doesn't add cljs.core here but some time later
      (is (= (dissoc (:requires a) 'cljs.core)
             (:requires b)))
      (is (= (:require-macros a) (:require-macros b)))
      (is (= (:uses a) (:uses b))) ;; CLJS has a bug that leaves :uses as nil if the only refered var was renamed
      (is (= (:use-macros a) (:use-macros b)))
      (is (= (:imports a) (:imports b)))
      (is (= (:renames a) (:renames b)))
      (is (= (:excludes a) (:excludes b)))
      (is (= (:rename-macros a) (:rename-macros b)))
      (is (= (:deps a) (:deps b)))
      (comment
        ;; cljs actually drops the docstring if separate from meta
        (is (= (meta (:name a))
               (meta (:name b))))))

    ;; meh, clojure.test doesn't show ex-data still ...
    (catch Exception e
      (repl/pst e))))
