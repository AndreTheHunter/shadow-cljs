(ns shadow.cljs.npm-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]))

(defmacro with-npm [[sym config] & body]
  `(let [~sym (npm/start)]
     (try
       ~@body
       (finally
         (npm/stop ~sym))
       )))

(deftest test-package-info
  (with-npm [x {}]
    (let [{:keys [file package-name] :as shortid}
          (npm/find-resource x nil "@material/animation" {})

          pkg-info
          (npm/find-package x package-name)]

      (prn file)
      (pprint shortid)
      (pprint (dissoc pkg-info :package-json))
      )))

(comment (deftest test-babel-transform
           (with-npm [x {}]

             (let [source "var foo = 1; export { foo };"]
               (pprint (npm/babel-convert-source x nil source))
               ))))

(deftest test-browser-overrides
  (with-npm [x {}]
    (let [index
          (-> (io/file "node_modules" "shortid" "lib" "index.js")
              (.getAbsoluteFile))

          _ (is (.exists index))

          rc
          (npm/find-resource x index "./util/cluster-worker-id" {:target :browser})]

      (pprint rc)

      )))

(deftest test-missing-package
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "i-dont-exist" {:target :browser})]

      (is (nil? rc))
      )))

(deftest test-resolve-to-global
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :global
                       :global "React"}}})]

      (pprint rc)
      )))

(deftest test-resolve-to-file
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :mode :release
             :resolve
             {"react" {:target :file
                       :file "test/dummy/react.dev.js"
                       :file-min "test/dummy/react.min.js"}}})]

      (pprint rc)
      )))

(deftest test-resolve-to-other
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :npm
                       :require "preact"}}})]

      (pprint rc)
      )))


(deftest test-relative-file
  (with-npm [x {}]
    (let [file-info
          (npm/find-resource x (io/file ".") "./src/test/foo" {})]

      (pprint file-info)
      )))


(deftest test-file-info-direct
  (with-npm [x {}]
    (let [file-info
          (npm/get-file-info x (.getCanonicalFile (io/file "test" "cjs" "entry.js")))]

      (pprint file-info)
      )))


(deftest test-file-info
  (with-npm [x {}]
    (let [file
          (-> (io/file "node_modules" "babel-runtime" "helpers" "typeof.js")
              (.getAbsoluteFile))

          rel-file
          (npm/find-relative x {:file file} "../core-js/symbol")

          file-info
          (npm/get-file-info* x file)]

      (pprint file-info)
      )))

(deftest test-package
  (with-npm [x {}]
    (pprint (npm/find-package* x "react"))
    ))


(deftest test-classpath
  (prn (npm-deps/get-deps-from-classpath)))

(deftest test-resolve-conflict-a
  (prn
    (npm-deps/resolve-conflicts
      [{:id "react" :version ">=15.0.0" :url "a"}
       {:id "react" :version "^16.0.0" :url "b"}])))

(deftest test-resolve-conflict-b
  (prn
    (npm-deps/resolve-conflicts
      [{:id "react" :version ">=16.0.0" :url "a"}
       {:id "react" :version ">=16.1.0" :url "b"}])))

(deftest test-is-installed
  (let [pkg {"dependencies" {"react" "^16.0.0"}}]
    (npm-deps/is-installed? {:id "react" :version "^15.0.0" :url "a"} pkg)))

(comment
  (install-deps
    {:node-modules {:managed-by :yarn}}
    [{:id "react" :version "^16.0.0"}
     {:id "react-dom" :version "^16.0.0"}]))

