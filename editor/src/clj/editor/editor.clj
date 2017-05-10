(ns editor.editor
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str])
  (:import
   (java.lang Runtime)
   (java.lang.management ManagementFactory RuntimeMXBean)))

(set! *warn-on-reflection* true)

(defn- startup-classpath
  []
  (.. ManagementFactory getRuntimeMXBean getClassPath))

(defn- startup-arguments
  []
  (.. ManagementFactory getRuntimeMXBean getInputArguments))

(defn- startup-command
  []
  #_(str/split (System/getProperty "sun.java.command") #"\s+")
  ["com.defold.editor.Start"])

(defn- startup-jvm
  []
  (str (io/file (System/getProperty "java.home") "bin" "java")))

(defn- command
  [args]
  (-> []
      (conj (startup-jvm))
      (conj "-cp" (startup-classpath))
      (into (startup-arguments))
      (into (startup-command))
      (into args)))

(defn start
  [& args]
  (let [cmd-array (into-array String (command args))]
    (apply sh/sh cmd-array)
    #_(.. Runtime getRuntime (exec cmd-array))))

(comment
  (def p (start "/Users/ragnardahlen/projects/defold-projects/issues/blank/game.project"))
  (clojure.pprint/pprint p)
  )
