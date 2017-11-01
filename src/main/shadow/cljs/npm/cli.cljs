(ns shadow.cljs.npm.cli
  (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["readline-sync" :as rl-sync] ;; FIXME: drop this?
            ["mkdirp" :as mkdirp]
            [cljs.core.async :as async]
    #_[cljs.tools.reader :as reader]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [goog.object :as gobj]
            [goog.string.format]
            [goog.string :refer (format)]
            [shadow.cljs.npm.util :as util]
            [shadow.cljs.npm.client :as client]
            [shadow.cljs.devtools.cli-opts :as opts]
            ))

(def jar-version
  (-> (js/require "../../package.json")
      (gobj/get "jar-version")))

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (fs/mkdirSync dir)))

;; FIXME: windows uses ;
(def cp-seperator
  (if (str/starts-with? js/process.platform "win")
    ";"
    ":"))

(defn is-directory? [path]
  (-> (fs/lstatSync path)
      (.isDirectory)))

(defn run [project-root java-cmd java-args proc-opts]
  (let [spawn-opts
        (-> {:cwd project-root
             :stdio "inherit"}
            (merge proc-opts)
            (clj->js))]

    (cp/spawnSync java-cmd (into-array java-args) spawn-opts)))

(defn run-java [project-root args opts]
  (let [result (run project-root "java" args opts)

        status
        (.-status result)]

    (cond
      (zero? status)
      true

      (pos? status)
      (throw (ex-info "java process exit with non-zero exit code" {:tag :java-exit :status status :result result}))

      (and (.-error result) (= "ENOENT" (.. result -error -errno)))
      (do (js/console.log "shadow-cljs - java not found, please install a Java8 SDK. (OpenJDK or Oracle)")
          (js/process.exit 1)
          ))))

(defn run-lein [project-root {:keys [lein] :as config} args]
  (let [{:keys [profile] :as lein-config}
        (cond
          (map? lein)
          lein
          (true? lein)
          {})

        lein-args
        (->> (concat
               (when profile
                 ["with-profile" profile])
               ["run" "-m" "shadow.cljs.devtools.cli" "--npm"]
               args)
             (into []))]

    (println "shadow-cljs - running: lein" (str/join " " lein-args))
    (run project-root "lein" lein-args {})))

(def default-config-str
  (util/slurp (path/resolve js/__dirname ".." "default-config.edn")))

(def default-config
  (reader/read-string default-config-str))

(defn ensure-config []
  (loop [root (path/resolve)]
    (let [config (path/resolve root "shadow-cljs.edn")]
      (cond
        (fs/existsSync config)
        config

        ;; check parent directory
        ;; might be in $PROJECT/src/demo it should find $PROJECT/shadow-cljs.edn
        (not= root (path/resolve root ".."))
        (recur (path/resolve root ".."))

        :else ;; ask to create default config in current dir
        false
        ))))

(defn run-init [opts]
  (let [config (path/resolve "shadow-cljs.edn")]
    (println "shadow-cljs - init")
    (println (str "- " config))

    (when (rl-sync/keyInYN "Create?")
      ;; FIXME: ask for default source path, don't just use one
      (fs/writeFileSync config default-config-str)
      (println "shadow-cljs - created default configuration")
      config
      )))

(defn modified-dependencies? [cp-file config]
  (let [cp (-> (util/slurp cp-file)
               (reader/read-string))]

    (or (not= (:version cp) (:version config))
        (not= (:dependencies cp) (:dependencies config))
        )))

(defn get-classpath [project-root {:keys [cache-root version] :as config}]
  (let [cp-file
        (path/resolve project-root cache-root "classpath.edn")

        ;; only need to rebuild the classpath if :dependencies
        ;; or the version changed
        updated?
        (when (or (not (fs/existsSync cp-file))
                  (modified-dependencies? cp-file config))
          ;; re-create classpath by running the java helper
          (let [jar (js/require "shadow-cljs-jar/path")]
            (run-java project-root ["-jar" jar] {:input (pr-str config)
                                                 :stdio [nil js/process.stdout js/process.stderr]})
            true))]

    ;; only return :files since the rest is just cache info
    (-> (util/slurp cp-file)
        (reader/read-string)
        (assoc :updated? updated?))))

(defn remove-class-files [path]
  (when (fs/existsSync path)
    ;; shadow-cljs - error ENOENT: no such file or directory, unlink '...'
    ;; I have no idea how readdir can find a file but then not find it when
    ;; trying to delete it?
    (doseq [file (into [] (fs/readdirSync path))
            :let [file (path/resolve path file)]]
      (cond
        (str/ends-with? file ".class")
        (when (fs/existsSync file)
          (try
            (fs/unlinkSync file)
            (catch :default e
              (prn [:failed-to-delete file]))))

        (is-directory? file)
        (remove-class-files file)

        :else
        nil
        ))))

(defn print-error [ex]
  (let [{:keys [tag] :as data}
        (ex-data ex)]

    (when (not= tag :java-exit)
      (println "shadow-cljs - error" (.-message ex)))
    ))

(defn check-dependencies! [{:keys [dependencies] :as config}]
  (when (seq (filter #(= 'org.clojure/clojure (first %)) dependencies))
    (throw (ex-info "Please remove org.clojure/clojure from your :dependencies." {}))))

(defn run-standalone
  [project-root {:keys [cache-root source-paths jvm-opts] :as config} args]
  (check-dependencies! config)
  (let [aot-path
        (path/resolve project-root cache-root "aot-classes")

        aot-version-path
        (path/resolve aot-path "version.txt")

        ;; only aot compile when the shadow-cljs version changes
        ;; changing the version of a lib (eg. reagent) does not need a new AOT compile
        ;; actual shadow-cljs deps should only change when shadow-cljs version itself changes
        aot-compile?
        (if-not (fs/existsSync aot-version-path)
          true
          (let [aot-version (util/slurp aot-version-path)]
            (not= jar-version aot-version)))

        classpath
        (get-classpath project-root config)

        classpath-str
        (->> (:files classpath)
             (concat [aot-path])
             (concat source-paths)
             (str/join cp-seperator))

        cli-args
        (-> []
            (into jvm-opts)
            (cond->
              aot-compile? ;; FIXME: maybe try direct linking?
              (into [(str "-Dclojure.compile.path=" aot-path)]))
            (into ["-cp" classpath-str "clojure.main"])
            (cond->
              aot-compile?
              (into ["-e" "(require 'shadow.cljs.aot-helper)"]))
            (into ["-m" "shadow.cljs.devtools.cli"
                   "--npm"])
            (into args))]


    (mkdirp/sync aot-path)

    (when aot-compile?
      (println "shadow-cljs - re-building aot cache on startup, that will take some time.")
      (remove-class-files aot-path)
      (fs/writeFileSync aot-version-path jar-version))

    (println "shadow-cljs - starting ...")
    (run-java project-root cli-args {})))

(def defaults
  {:cache-root "target/shadow-cljs"
   :version jar-version
   :dependencies []})

(defn merge-config-with-cli-opts [config {:keys [options] :as opts}]
  (let [{:keys [dependencies]} options]
    (-> config
        (cond->
          (seq dependencies)
          (update :dependencies into dependencies)
          ))))

(defn prettier-m2-path [path]
  (if-let [idx (str/index-of path ".m2")]
    ;; strip .m2/repository/
    (str "[maven] " (subs path (+ idx 15)))
    path
    ))

(defn print-cli-info [project-root config-path {:keys [cache-root source-paths] :as config} opts]
  (println "=== Version")
  (println "cli:           " (-> (js/require "../../package.json")
                                 (gobj/get "version")))
  (println "jar-version:   " jar-version)
  (println "config-version:" (:version config))
  (println)

  (println "=== Paths")
  (println "cli:    " js/__filename)
  (println "config: " config-path)
  (println "project:" project-root)
  (println "cache:  " cache-root)
  (println)

  (println "=== Java")
  (run-java project-root ["-version"] {})
  (println)

  (println "=== Source Paths")
  (doseq [source-path source-paths]
    (println (path/resolve project-root source-path)))
  (println)

  (println "=== Dependencies")
  (let [cp-file (path/resolve project-root cache-root "classpath.edn")]
    (println "cache-file:" cp-file)
    (when (fs/existsSync cp-file)
      (let [{:keys [files] :as cp-data}
            (-> (util/slurp cp-file)
                (reader/read-string))]

        (doseq [file files]
          (println (prettier-m2-path file)))
        )))
  (println)
  )

(defn dump-script-state []
  (println "--- active requests")
  (prn (js/process._getActiveRequests))
  (println "--- active handles")
  (prn (js/process._getActiveHandles)))

(defn read-config [config-path opts]
  (try
    (-> (util/slurp config-path)
        (reader/read-string)
        (merge-config-with-cli-opts opts))
    (catch :default ex
      ;; FIXME: missing tools.reader location information
      ;; FIXME: show error location with excerpt like other warnings
      (throw (ex-info (format "failed reading config file: %s" config-path) {:config-path config-path} ex)))))

(defn guess-node-package-manager [project-root config]
  (or (get-in config [:node-modules :managed-by])
      (let [yarn-lock (path/resolve project-root "yarn.lock")]
        (when (fs/existsSync yarn-lock)
          :yarn))
      :npm))

(defn check-project-install! [project-root config]
  (let [package-json-file
        (path/resolve project-root "package.json")]

    (or (fs/existsSync (path/resolve "node_modules" "shadow-cljs"))
        (and (fs/existsSync package-json-file)
             (let [pkg (js->clj (js/require package-json-file))]
               (or (get-in pkg ["devDependencies" "shadow-cljs"])
                   (get-in pkg ["dependencies" "shadow-cljs"]))))

        ;; not installed
        (do (println "shadow-cljs not installed in project.")
            (println "")

            (if-not (rl-sync/keyInYN "Add it now?")
              false
              (let [[pkg-cmd pkg-args]
                    (case (guess-node-package-manager project-root config)
                      :yarn
                      ["yarn" ["add" "--dev" "shadow-cljs"]]
                      :npm
                      ["npm" ["install" "--save-dev" "shadow-cljs"]])]

                (println (str "Running: " pkg-cmd " " (str/join " " pkg-args)))

                (cp/spawnSync pkg-cmd (into-array pkg-args) #js {:cwd project-root
                                                                 :stdio "inherit"})
                true))))))

(defn ^:export main [args]

  ;; https://github.com/tapjs/signal-exit
  ;; without this the shadow-cljs process leaves orphan java processes
  ;; that do not exit when the node process is killed (by closing the terminal)
  ;; just adding this causes the java processes to exit properly ...
  ;; I do not understand why ... but I can still use spawnSync this way so I'll take it
  (let [onExit (js/require "signal-exit")]
    (onExit (fn [code signal])))

  (try
    (let [{:keys [action builds options summary errors] :as opts}
          (opts/parse args)]

      (cond
        (or (:help options)
            (= action :help))
        (opts/help opts)

        (= action :init)
        (run-init opts)

        :else
        (let [config-path (ensure-config)]
          (if-not config-path
            (do (println "Could not find shadow-cljs.edn config file.")
                (println "To create one run:")
                (println "  shadow-cljs init"))

            (let [project-root
                  (path/dirname config-path)

                  args
                  (into [] args) ;; starts out as JS array

                  config
                  (read-config config-path opts)]

              (when (check-project-install! project-root config)

                (if (not (map? config))
                  (do (println "shadow-cljs - old config format no longer supported")
                      (println config-path)
                      (println "  previously a vector was used to define builds")
                      (println "  now {:builds the-old-vector} is expected"))

                  (let [{:keys [cache-root version] :as config}
                        (merge defaults config)

                        server-pid
                        (path/resolve project-root cache-root "cli-repl.port")]

                    (println "shadow-cljs - config:" config-path "version:" version)

                    (cond
                      (:cli-info options)
                      (print-cli-info project-root config-path config opts)

                      (fs/existsSync server-pid)
                      (client/run project-root config server-pid opts args)

                      (:lein config)
                      (run-lein project-root config args)

                      :else
                      (run-standalone project-root config args)
                      )))))))))
    (catch :default ex
      (print-error ex)
      (js/process.exit 1))))
