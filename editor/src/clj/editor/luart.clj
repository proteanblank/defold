(ns editor.luart
  (:require [clojure.string :as string]
            [editor.debugging.mobdebug :as mobdebug]
            [clojure.java.io :as io])
  (:import [org.luaj.vm2 LuaNil LuaValue LuaInteger LuaDouble LuaBoolean LuaString LuaTable Varargs LuaValue$None LuaFunction]
           [clojure.lang IPersistentVector IPersistentMap Keyword Fn]
           [org.luaj.vm2.lib VarArgFunction]
           [org.luaj.vm2.lib.jse JsePlatform]))

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
    #_(proxy [VarArgFunction] []
        (invoke [varargs]
          (let [args (if (instance? LuaValue varargs)
                       [(lua->clj varargs)]
                       (lua->clj varargs))]
            (LuaValue/varargsOf (into-array LuaValue [(clj->lua (apply f args))]))))))
  LuaFunction
  (lua->clj [f]
    f
    #_(fn [& args]
        (lua->clj (.invoke f (LuaValue/varargsOf (into-array LuaValue (mapv clj->lua args))))))))

(defn- set-globals! [^LuaValue globals m]
  (doseq [[k v] m]
    (.set globals (clj->lua k) (clj->lua v))))

#_(let [globals (JsePlatform/standardGlobals)
        closure (lua->clj (.load globals "print(\"top-level print!\")"))]
    (closure)
    (closure))

#_(let [globals (JsePlatform/debugGlobals)
        closure (lua->clj (.load globals "return require('_unpack._defold.debugger.edn').encode(_G);"))]
    (mobdebug/lua-value->structure-string (#'mobdebug/decode-serialized-data (closure))))

#_(let [globals (doto (JsePlatform/standardGlobals)
                  (set-globals! {:pprint clojure.pprint/pprint}))
        closure (lua->clj (.load globals "return function (x) pprint(x); end;"))]
    ((closure) {[[:a]] :b}))
