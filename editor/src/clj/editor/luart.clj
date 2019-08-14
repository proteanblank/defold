(ns editor.luart
  (:refer-clojure :exclude [read eval])
  (:require [clojure.string :as string]
            [editor.debugging.mobdebug :as mobdebug]
            [clojure.java.io :as io]
            [editor.console :as console]
            [editor.workspace :as workspace])
  (:import [org.luaj.vm2 LuaNil LuaValue LuaInteger LuaDouble LuaBoolean LuaString LuaTable Varargs LuaValue$None LuaFunction Globals LoadState LuaClosure Prototype]
           [clojure.lang IPersistentVector IPersistentMap Keyword Fn]
           [org.luaj.vm2.lib VarArgFunction PackageLib Bit32Lib TableLib StringLib CoroutineLib]
           [org.luaj.vm2.lib.jse JsePlatform JseBaseLib JseMathLib JseIoLib JseOsLib]
           [java.io PrintStream BufferedWriter Writer PipedInputStream PipedOutputStream BufferedReader InputStreamReader OutputStream ByteArrayInputStream]
           [org.apache.commons.io.output WriterOutputStream]
           [org.luaj.vm2.compiler LuaC]
           [java.nio.charset Charset]))

(set! *warn-on-reflection* true)

(defprotocol LuaConversions
  (clj->lua ^LuaValue [this])
  (lua->clj [this]))

(extend-protocol LuaConversions
  nil
  (clj->lua [_] LuaValue/NIL)
  LuaNil
  (lua->clj [_] nil)
  LuaValue$None
  (lua->clj [_] nil)

  Number
  (clj->lua [n] (LuaValue/valueOf (double n)))
  LuaInteger
  (lua->clj [i] (.tolong i))
  LuaDouble
  (lua->clj [d] (.todouble d))

  Boolean
  (clj->lua [b] (LuaValue/valueOf b))
  LuaBoolean
  (lua->clj [b] (.toboolean b))

  String
  (clj->lua [s] (LuaValue/valueOf s))
  LuaString
  (lua->clj [s] (.tojstring s))

  IPersistentVector
  (clj->lua [v]
    (LuaValue/listOf (into-array LuaValue (mapv clj->lua v))))
  IPersistentMap
  (clj->lua [m]
    (LuaValue/tableOf
      (into-array LuaValue
                  (into []
                        (comp
                          cat
                          (map clj->lua))
                        m))))
  Varargs
  (lua->clj [varargs]
    (mapv #(lua->clj (.arg varargs (inc %))) (range (.narg varargs))))
  LuaTable
  (lua->clj [t]
    (let [kvs (persistent!
                (loop [k LuaValue/NIL
                       acc (transient [])]
                  (let [pair (.next t k)]
                    (if (.isnil (.arg pair 1))
                      acc
                      (recur (.arg pair 1) (conj! acc (lua->clj pair)))))))]
      (if (= (mapv first kvs)
             (range 1 (inc (count kvs))))
        (mapv second kvs)
        (into (array-map) kvs))))

  Keyword
  (clj->lua [k] (LuaValue/valueOf (string/replace (name k) "-" "_")))

  Object
  (clj->lua [x] (LuaValue/valueOf (pr-str x)))

  Fn
  (clj->lua [f]
    f
    (proxy [VarArgFunction] []
      (invoke [varargs]
        (let [args (if (instance? LuaValue varargs)
                     [(lua->clj varargs)]
                     (lua->clj varargs))]
          (LuaValue/varargsOf (into-array LuaValue [(clj->lua (apply f args))]))))))
  LuaFunction
  (lua->clj [f]
    f))

(defn- set-globals! [^LuaValue globals m]
  (doseq [[k v] m]
    (.set globals (clj->lua k) (clj->lua v))))

(defn- line-print-stream [f]
  (let [sb (StringBuilder.)]
    (PrintStream.
      (WriterOutputStream.
        (PrintWriter-on #(doseq [^char ch %]
                           (if (= \newline ch)
                             (let [str (.toString sb)]
                               (.delete sb 0 (.length sb))
                               (f str))
                             (.append sb ch)))
                        nil)
        "UTF-8")
      true
      "UTF-8")))

(defn make-env
  ^Globals [find-resource extra-globals]
  (doto (Globals.)
    (.load (proxy [JseBaseLib] []
             (findResource [filename]
               (let [^JseBaseLib this this]
                 (prn :find filename)
                 (some-> (find-resource (str "/" filename))
                         io/input-stream)
                 #_(proxy-super findResource filename)))))
    (.load (PackageLib.))
    (.load (Bit32Lib.))
    (.load (TableLib.))
    (.load (StringLib.))
    (.load (CoroutineLib.))
    (.load (JseMathLib.))
    (.load (JseIoLib.))
    (.load (JseOsLib.))
    (LoadState/install)
    (LuaC/install)
    (-> (.-STDOUT) (set! (line-print-stream #(console/append-console-entry! :extension-out %))))
    (-> (.-STDERR) (set! (line-print-stream #(console/append-console-entry! :extension-err %))))
    (set-globals! extra-globals)))

(defn evaluate
  ^LuaValue [^Globals globals ^String str ^String chunk-name]
  (.call (.load globals str chunk-name)))

(defn read
  ^Prototype [^String chunk chunk-name]
  (.compile LuaC/instance
            (ByteArrayInputStream. (.getBytes chunk (Charset/forName "UTF-8")))
            chunk-name))

(defn eval [prototype env]
  (.call (LuaClosure. prototype env)))

#_(let [globals (make-env)
        closure (lua->clj (.load globals "return function (x) print(x); print(x); print(1 .. 4); print(\"blёp\\nmlep\"); end;"))]
    (.call (.call closure) (clj->lua {[[:a]] :b})))
