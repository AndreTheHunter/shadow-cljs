(defproject thheller/shadow-devtools "0.1.44"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[com.cognitect/transit-cljs "0.8.239"]
                 [aleph "0.4.1"]
                 [org.clojure/clojure "1.9.0-alpha7"]
                 [org.clojure/clojurescript "1.9.93"]
                 [org.clojure/core.async "0.2.385"]
                 [thheller/shadow-client "1.0.161"]
                 [thheller/shadow-build "1.0.221"]]

  :source-paths ["src/clj"
                 "src/cljs"]

  :java-source-paths ["src/java"]

  :profiles {:dev {:source-paths ["src/dev"]
                   ;; :repl-options {:nrepl-middleware [build/browser-dev-nrepl]}

                   }})
