(ns shadow.build.npm
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [shadow.build.resource :as rc]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [cljs.compiler :as cljs-comp]
            [clojure.tools.logging :as log])
  (:import (java.io File)
           (com.google.javascript.jscomp SourceFile CompilerOptions CompilerOptions$LanguageMode)
           (com.google.javascript.jscomp.deps ModuleNames)
           (shadow.build.closure JsInspector)))

(def NPM-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  (-> (io/resource "shadow/build/npm.clj")
      (.openConnection)
      (.getLastModified)))


(def CLOSURE-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  ;; this is bit ugly but since files are re-compiled by the shadow.build.closure
  ;; namespace (which depends on this one) we need it to properly invalidate
  ;; the cache
  (-> (io/resource "shadow/build/closure.clj")
      (.openConnection)
      (.getLastModified)))


(defn service? [x]
  (and (map? x) (::service x)))

(defn read-package-json
  "this caches the contents package.json files since we may access them quite often when resolving deps"
  [{:keys [index-ref] :as state} ^File file]
  (let [last-modified (.lastModified file)

        cached
        (get-in @index-ref [:package-json-cache file])]

    (if (and cached (= last-modified (:last-modified cached)))
      (:content cached)
      (let [{:strs [dependencies name version browser] :as package-json}
            (-> (slurp file)
                (json/read-str))

            package-dir
            (.getParentFile file)

            content
            (-> {:package-name name
                 :package-dir package-dir
                 :package-json package-json
                 :version version
                 :dependencies (into #{} (keys dependencies))}
                (cond->
                  (string? browser)
                  (assoc :browser browser)
                  (map? browser)
                  (assoc :browser-overrides browser)))
            ]

        (swap! index-ref assoc-in [:package-json-cache file] {:content content
                                                              :last-modified last-modified})
        content
        ))))

(defn find-package-json [file]
  (loop [root (if (.isDirectory file)
                (.getParentFile file)
                file)]
    (when root
      (let [package-json (io/file root "package.json")]
        (if (and (.exists package-json)
                 (.isFile package-json))
          package-json
          (recur (.getParentFile root))
          )))))

(defn find-package-for-file [npm file]
  (when-let [package-json-file (find-package-json file)]
    (read-package-json npm package-json-file)))

(defn test-file ^File [^File dir name]
  (when name
    (let [path
          (-> (io/file dir name)
              (.getCanonicalFile))]
      (when (.exists path)
        path))))

(defn test-file-exts [{:keys [extensions] :as npm} ^File dir name]
  (reduce
    (fn [_ ext]
      (when-let [path (test-file dir (str name ext))]
        (reduced path)))
    nil
    extensions))



(defn find-package* [{:keys [node-modules-dir] :as npm} package-name]
  ;; this intentionally only checks
  ;; $PROJECT_ROOT/node_modules/package-name
  ;; never
  ;; $PROJECT_ROOT/node_modules/pkg-a/node_modules/pkg-b
  ;; never outside $PROJECT_ROOT
  ;; not allowing anything outside the project because of
  ;; https://github.com/technomancy/leiningen/wiki/Repeatability

  (let [package-dir
        (io/file node-modules-dir package-name)]

    (when (.exists package-dir)

      (let [package-json-file
            (io/file package-dir "package.json")]

        (when-not (.exists package-json-file)
          (throw (ex-info (format "cannot find package.json for package %s at %s" package-name package-json-file)
                   {:tag ::missing-package-json
                    :package-name package-name
                    :package-json-file package-json-file})))

        (read-package-json npm package-json-file)))))

(defn find-package [{:keys [index-ref] :as npm} package-name]
  {:pre [(string? package-name)
         (seq package-name)]}
  (or (get-in @index-ref [:packages package-name])
      (let [pkg-info (find-package* npm package-name)]
        (swap! index-ref assoc-in [:packages package-name] pkg-info)
        pkg-info
        )))

(defn split-package-require
  "@scoped/thing -> [@scoped/thing nil]
   @scoped/thing/foo -> [@scoped/thing foo]
   unscoped -> [unscoped nil]
   unscoped/foo -> [unscoped foo]"
  [entry]
  (let [slash-idx
        (str/index-of entry "/")

        slash-idx
        (if-not (str/starts-with? entry "@")
          slash-idx
          (str/index-of entry "/" (inc slash-idx)))]

    (if-not slash-idx
      [entry nil]
      [(subs entry 0 slash-idx)
       ;; some requires might have trailing slash, which I guess means it is supposed to use main?
       ;; "string_decoder/"
       (let [suffix (subs entry (inc slash-idx))]
         (when (not= suffix "")
           suffix))])))

(defn find-package-require [npm require]
  (let [[package-name suffix]
        (split-package-require require)

        {:keys [package-dir package-json entry-file] :as package}
        (find-package npm package-name)]

    (cond
      (nil? package)
      nil

      ;; "react-dom", use entry-file
      (nil? suffix)
      (let [entries
            (->> (:main-keys npm)
                 (map #(get package-json %))
                 (remove nil?)
                 (into []))

            [entry entry-file]
            (or (reduce
                  (fn [_ entry]
                    (when-let [file (or (test-file package-dir entry)
                                        ;; some libs have main:"some/dir/foo" without the .js
                                        (test-file-exts npm package-dir entry))]

                      (reduced [entry file])))
                  nil
                  ;; we only want the first one in case more exist
                  entries)

                ;; FIXME: test-file-exts may have chosen index.json, not index.js
                ["index.js"
                 (test-file-exts npm package-dir "index")])]

        (if-not entry-file
          (throw (ex-info
                   (format "module without entry or suffix: %s" require)
                   {:package package
                    :entry require}))

          entry-file))

      ;; "react-dom/server" -> react-dom/server.js
      ;; "core-js/library/fn-symbol" is a directory, need to resolve to index.js
      :else
      (let [file-or-dir
            (test-file package-dir suffix)

            file
            (cond
              (not file-or-dir)
              (test-file-exts npm package-dir suffix)

              (and file-or-dir (.isDirectory file-or-dir))
              (test-file-exts npm file-or-dir "index")

              :else
              file-or-dir)]

        (when-not (and file (.isFile file))
          (throw (ex-info (format "could not find module-entry: %s" require)
                   {:require require
                    :entry require
                    :package package})))

        file
        ))))

(defn find-relative [npm ^File relative-to ^String require]
  (when-not relative-to
    (throw (ex-info
             (format "can only resolve relative require with a file reference: %s" require)
             {:entry require})))

  (let [rel-dir
        (cond
          (.isFile relative-to)
          (.getParentFile relative-to)

          (.isDirectory relative-to)
          relative-to

          :else
          (throw (ex-info (format "can't find %s from %s" require relative-to)
                   {:tag ::find-relative
                    :relative-to relative-to
                    :require require})))

        file
        (test-file rel-dir require)

        file
        (cond
          (not file)
          (test-file-exts npm rel-dir require)

          ;; babel-runtime has a ../core-js/symbol require
          ;; core-js/symbol is a directory
          ;; core-js/symbol.js is a file
          ;; so for each directory first test if there is file by the same name
          (.isDirectory file)
          (or (test-file-exts npm rel-dir require)
              (test-file-exts npm file "index"))

          :else
          file)]

    (when-not (and file (.isFile file))
      (throw (ex-info (format "failed to resolve: %s from %s" require relative-to require)
               {:relative-to relative-to
                :entry require})))

    file))

(defn maybe-convert-goog [dep]
  (if-not (str/starts-with? dep "goog:")
    dep
    (symbol (subs dep 5))))

(defn get-file-info*
  "extract some basic information from a given file, does not resolve dependencies"
  [{:keys [compiler project-dir] :as npm} ^File file]
  {:pre [(service? npm)
         (util/is-file-instance? file)
         (.isAbsolute file)]}

  (let [abs-path
        (.toPath project-dir)

        file-path
        (.toPath file)

        _ (when-not (.startsWith file-path abs-path)
            (throw (ex-info (format "files outside the project are not allowed: %s" file-path)
                     {:file file})))

        resource-name
        (->> (.relativize abs-path file-path)
             (str)
             (rc/normalize-name))

        ns (-> (ModuleNames/fileToModuleName resource-name)
               ;; (cljs-comp/munge) ;; FIXME: the above already does basically the same, does it cover everything?
               (symbol))

        last-modified
        (.lastModified file)

        ;; the only reliable way to determine if something belongs to a package
        ;; is by looking for the package.json and parsing the name
        ;; we can't just remember the entry require("react") since that may
        ;; require("./lib/React.js") which also belongs to the react package
        ;; so we must determine this from the file alone not by the way it was required
        {:keys [package-name]}
        (find-package-for-file npm file)
        ]

    ;; require("../package.json").version is a thing
    ;; no need to parse it since it can't have any require/import/export
    (-> (if (str/ends-with? (.getName file) ".json")
          {:resource-id [::resource resource-name]
           :resource-name resource-name
           :output-name (str ns ".js")
           :json true
           :type :npm
           :file file
           :last-modified last-modified
           :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP last-modified]
           :ns ns
           :provides #{ns}
           :requires #{}
           :source (slurp file)
           :js-deps []}

          ;; FIXME: check if a .babelrc applies and then run source through babel first
          ;; that should take care of .jsx and others if I actually want to support that?
          (let [source
                (slurp file)

                ;; all requires are collected into
                ;; :js-requires ["foo" "bar/thing" "./baz]
                ;; all imports are collected into
                ;; :js-imports ["react"]
                {:keys [js-requires js-imports js-errors js-warnings js-invalid-requires js-language] :as info}
                (JsInspector/getFileInfo
                  compiler
                  ;; SourceFile/fromFile seems to leak file descriptors
                  (SourceFile/fromCode (.getAbsolutePath file) source))

                js-deps
                (->> (concat js-requires js-imports)
                     (map maybe-convert-goog)
                     (distinct)
                     (into []))]

            (when (seq js-errors)
              (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                       (assoc info :tag ::errors))))

            ;; moment.js has require('./locale/' + name); inside a function
            ;; it shouldn't otherwise hurt though
            (doseq [{:keys [line column]} js-invalid-requires]
              (log/warnf "invalid JS require call in file %s:%d:%d" file line column))

            (-> info
                (assoc
                  :resource-id [::resource resource-name]
                  :resource-name resource-name
                  ;; not using flat-name since resource-name may contain @scoped/alias
                  :output-name (str ns ".js")
                  :type :npm
                  :file file
                  :last-modified last-modified
                  :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP last-modified]
                  :ns ns
                  :provides #{ns}
                  :requires #{}
                  :source source
                  :js-deps js-deps
                  :deps (into '[shadow.js] js-deps)
                  ))))

        (cond->
          package-name
          (assoc :package-name package-name)))))

(defn get-file-info [{:keys [index-ref] :as npm} ^File file]
  {:pre [(service? npm)]}
  (or (get-in @index-ref [:files file])
      (let [file-info (get-file-info* npm file)]
        (swap! index-ref assoc-in [:files file] file-info)
        file-info
        )))

(defn find-package-resource [npm require]
  (when-let [file (find-package-require npm require)]
    (get-file-info npm file)))

(def empty-rc
  (let [ns 'shadow.empty]
    {:resource-id [::empty "shadow$empty.js"]
     :resource-name "shadow$empty.js"
     :output-name "shadow$empty.js"
     :type :npm
     :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps '[shadow.js]
     :source ""}))

(defn js-resource-for-global
  "a dependency might come from something already included in the page by other means

   a config like:
   {\"react\" {:type :global :global \"React\"}}

   means require(\"react\") returns the global React instance"
  [require {:keys [global] :as pkg}]
  (let [ns (symbol (ModuleNames/fileToModuleName require))]
    {:resource-id [::global require]
     :resource-name (str "global$" ns ".js")
     :output-name (str ns ".js")
     :type :npm
     :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps []
     :source (str "module.exports=(" global ");")}))

(defn js-resource-for-file
  "if we want to include something that is not on npm or we want a custom thing
  {\"react\" {:type :file :file \"path/to/my/react.js\"}}"

  [npm require {:keys [file file-min] :as cfg} {:keys [mode] :as require-ctx}]
  (let [file
        (-> (if (and (= :release mode) (seq file-min))
              (io/file file-min)
              (io/file file))
            (.getCanonicalFile))]
    (when-not (.exists file)
      (throw (ex-info "file override for require doesn't exist" {:file file :require require :config cfg})))

    (get-file-info npm file)
    ))

;; https://github.com/webpack/node-libs-browser/blob/master/index.js
;; using this package so have the same dependencies that webpack would use
(def node-libs-browser
  {"child_process" false
   "cluster" false
   "console" "console-browserify"
   "constants" "constants-browserify"
   "crypto" "crypto-browserify"
   "dgram" false
   "dns" false
   "domain" "domain-browser"
   "fs" false
   "http" "stream-http"
   "https" "https-browserify"
   "module" false
   "net" false
   "os" "os-browserify/browser.js"
   "path" "path-browserify"
   "process" "process/browser.js"
   "querystring" "querystring-es3"
   "readline" false
   "repl" false
   "stream" "stream-browserify"
   "_stream_duplex" "readable-stream/duplex.js"
   "_stream_passthrough" "readable-stream/passthrough.js"
   "_stream_readable" "readable-stream/readable.js"
   "_stream_transform" "readable-stream/transform.js"
   "_stream_writable" "readable-stream/writable.js"
   "string_decoder" "string_decoder"
   "sys" "util/util.js"
   "timers" "timers-browserify"
   "tls" false
   "tty" "tty-browserify"
   "url" "url"
   "util" "util/util.js"
   "vm" "vm-browserify"
   "zlib" "browserify-zlib"})

;; FIXME: this should work with URLs now that we can easily resolve into jars
;; but since David pretty clearly said the relative requires are not going to happen
;; I'm not worried about finding relative requires in jars
;; https://dev.clojure.org/jira/browse/CLJS-2061?focusedCommentId=46191&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-46191

(defn find-resource*
  [{:keys [project-dir] :as npm} ^File require-from ^String require require-ctx]
  {:pre [(service? npm)
         (or (nil? require-from)
             (instance? File require-from))
         (string? require)
         (map? require-ctx)]}

  (let [{:keys [browser-overrides package-dir] :as pkg}
        (when require-from
          (find-package-for-file npm require-from))]

    (cond
      ;; absolute is relative to the project, no outside dependencies are allowed
      (util/is-absolute? require)
      (let [file (io/file project-dir (subs require 1))]
        (when-not (and (.exists file)
                       (.isFile file))
          (throw (ex-info "absolute require not found" {:require-from require-from :require require :file file})))

        (get-file-info npm file))

      ;; browser override makes things complicated
      ;; https://github.com/defunctzombie/package-browser-field-spec

      ;; "browser":{"./lib/some-file.js":"./lib/some-other-file.js"}
      ;; the file overrides are relative to the project folder
      ;; but the actual require may be require("./some-file.js") since that is relative in the file
      ;; so find the normal file first, when found we relativize the path to the package dir
      ;; if that path was overriden we swap it, otherwise use the file we found first
      (util/is-relative? require)
      (let [file (find-relative npm require-from require)

            override
            (when pkg
              (let [package-path
                    (.toPath package-dir)

                    rel-name
                    (str "./" (.relativize package-path (.toPath file)))]

                ;; FIXME: I'm almost certain that browser allows overriding without extension
                ;; "./lib/some-file":"./lib/some-other-file"
                (get browser-overrides rel-name)))]

        (cond
          (nil? override)
          (get-file-info npm file)

          ;; FIXME: is "./lib/some-file.js":"some-package" allowed?
          (string? override)
          (let [override-file
                (-> (io/file package-dir override)
                    (.getCanonicalFile))]

            (when-not (.exists override-file)
              (throw (ex-info "override to file that doesn't exist" {:require-from require-from
                                                                     :require require
                                                                     :file file
                                                                     :override override
                                                                     :override-file override-file})))
            (get-file-info npm override-file))

          ;; FIXME: is that allowed?
          (false? override)
          (throw (ex-info "TBD, rel-file is false" {:package-dir package-dir}))

          :else
          (throw (ex-info "invalid override" {:package-dir package-dir
                                              :require require
                                              :override override}))
          ))


      ;; package-require
      :else
      (let [override
            (let [override (get-in pkg [:browser-overrides require] ::not-found)]
              (if (not= ::not-found override)
                override
                ;; FIXME: should check require-ctx :browser, which should be our only target but who knows
                (get node-libs-browser require)))]

        (cond
          (nil? override)
          (find-package-resource npm require)

          ;; "browser":{"util":false} means we should ignore a package import
          ;; since we must resolve to something we just resolve to an empty file
          (false? override)
          empty-rc

          ;; FIXME: "util":"./file-in-package.js" - is that allowed?
          (util/is-relative? require)
          (throw (ex-info "browser override from package to relative" {:require-from require-from :require require :override override}))

          ;; "foo":"bar"
          ;; FIXME: should this call find-resource so one resolve override can link to another?
          :else
          (find-resource* npm require-from override require-ctx)
          )))))

(defn find-resource
  [{:keys [project-dir] :as npm} ^File require-from ^String require require-ctx]
  {:pre [(service? npm)
         (or (nil? require-from)
             (instance? File require-from))
         (string? require)
         (map? require-ctx)]}

  ;; FIXME: this should probably be moved to shadow.build.resolve?

  ;; per build :resolve config that may override where certain requires go
  ;; FIXME: should this only allow overriding package requires?
  ;; relative would need to be relative to the project, otherwise a generic
  ;; "./something.js" would override anything from any package
  ;; just assume ppl will only override packages for now
  (let [resolve-config (:resolve require-ctx)]

    (if-not (contains? resolve-config require)
      (find-resource* npm require-from require require-ctx)

      (let [{:keys [target] :as cfg} (get resolve-config require)]
        ;; FIXME: defmulti?
        (case target
          ;; {"react" {:target :global :global "React"}}
          :global
          (js-resource-for-global require cfg)

          ;; {"react" {:target :file :file "some/path.js"}}
          :file
          (js-resource-for-file npm require cfg require-ctx)

          ;; {"react" {:target :npm :require "preact"}}
          :npm
          (let [other
                (if (and (= :release (:mode require-ctx)) (contains? cfg :require-min))
                  (:require-min cfg)
                  (:require cfg))]

            ;; FIXME: maybe allow to add some additional stuff?
            (when (= require other)
              (throw (ex-info "can't resolve to self" {:require require :other other})))

            (find-resource npm require-from other require-ctx))

          (throw (ex-info "unknown resolve target" {:require require
                                                    :config cfg})))))))

;; FIXME: allow configuration of :extensions :main-keys
;; maybe some closure opts
(defn start []
  (let [index-ref
        (atom {:files {}
               :packages {}
               :package-json-cache {}})

        co
        (doto (CompilerOptions.)
          ;; FIXME: good idea to disable ALL warnings?
          ;; I think its fine since we are just looking for require anyways
          ;; if the code has any other problems we'll get to it when importing
          (.resetWarningsGuard)
          ;; should be the highest possible option, since we can't tell before parsing
          (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT_2017))

        cc ;; FIXME: error reports still prints to stdout
        (doto (com.google.javascript.jscomp.Compiler.)
          (.disableThreads)
          (.initOptions co))

        project-dir
        (-> (io/file "")
            (.getAbsoluteFile))

        ;; FIXME: allow configuration of this
        node-modules-dir
        (io/file project-dir "node_modules")]

    {::service true
     :index-ref index-ref
     :compiler cc
     :compiler-options co
     ;; JVM working dir always
     :project-dir project-dir
     :node-modules-dir node-modules-dir
     ;; FIXME: if a build ever needs to configure these we can't use the shared npm reference
     :extensions [".js" ".json"]
     :main-keys [#_ "module" #_ "jsnext:main" "main"]
     }))

(defn stop [npm])
