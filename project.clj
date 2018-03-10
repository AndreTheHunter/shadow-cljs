(defproject thheller/shadow-cljs "2.2.7"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :jvm-opts
  ~(-> ["-Dfile.encoding=UTF-8"]
     (cond->
       (-> (System/getProperty "java.version") (.startsWith "9."))
       (conj "--add-modules" "java.xml.bind")))

  :javac-options
  ["-target" "1.8"
   "-source" "1.8"]

  :dependencies
  [[org.clojure/clojure "1.9.0"]
   ;; java9, not required for java8
   ;; [javax.xml.bind/jaxb-api "2.3.0"]

   ;; [org.clojure/spec.alpha "0.1.108"]
   ;; [org.clojure/core.specs.alpha "0.1.10"]

   [org.clojure/java.classpath "0.2.3"]
   [org.clojure/data.json "0.2.6"]

   [org.clojure/tools.logging "0.4.0"]
   [org.clojure/tools.cli "0.3.5"]
   [org.clojure/tools.nrepl "0.2.13"]
   [org.clojure/tools.reader "1.1.2"]

   [com.cognitect/transit-clj "0.8.300"]
   [com.cognitect/transit-cljs "0.8.243"]

   [org.clojure/core.async "0.4.474"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.9.946"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded]]

   [com.google.javascript/closure-compiler-unshaded "v20180204"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.2"]

   [io.undertow/undertow-core "1.4.22.Final"]
   ;; tools.deps won't include runtime deps properly
   [org.jboss.xnio/xnio-nio "3.3.8.Final"]

   [hiccup "1.0.5"]
   [ring/ring-core "1.6.3"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [expound "0.4.0"]
   [fipp "0.6.12"]

   ;; experimental
   [hawk "0.2.11"]
   [thheller/shadow-cljsjs "0.0.7"]]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :java-source-paths
  ["src/main"]

  :profiles
  {:provided
   {:source-paths
    ["src/ui-release"]}
   :dev
   {:source-paths
    ["src/dev"
     "src/repl"]

    :dependencies
    [[org.slf4j/slf4j-log4j12 "1.7.25"]
     [log4j "1.2.17"]
     [org.clojure/tools.namespace "0.2.11"]]}

   :aot
   {:aot [repl]}

   :cljs
   {:java-opts ^:replace []
    :dependencies
    []
    :repl-options
    {:nrepl-middleware
     [shadow.cljs.devtools.server.nrepl/cljs-load-file
      shadow.cljs.devtools.server.nrepl/cljs-eval
      shadow.cljs.devtools.server.nrepl/cljs-select
      ;; required by some tools, not by shadow-cljs.
      cemerick.piggieback/wrap-cljs-repl]}
    :source-paths
    ["src/dev"
     "src/gen"
     "src/test"]}})
