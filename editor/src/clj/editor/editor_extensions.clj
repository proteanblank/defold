(ns editor.editor-extensions
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.luart :as luart]
            [editor.console :as console]
            [clojure.string :as string])
  (:import [org.luaj.vm2 LuaFunction LuaValue LuaError]))

(set! *warn-on-reflection* true)

(g/defnode EditorExtensions
  (input project-prototypes g/Any :array :substitute gu/array-subst-remove-errors)
  (input library-prototypes g/Any :array :substitute gu/array-subst-remove-errors)
  #_(output extensions g/Any :cached (g/fnk [extensions]
                                       (transduce
                                         cat
                                         (completing
                                           #(update %1 (key %2) (fnil conj []) (val %2)))
                                         {}
                                         extensions))))

(defn reload [project kind]
  (g/user-data-swap!
    (g/node-value project :editor-extensions)
    :ext-map
    (fn [ext-map]
      (let [extensions (g/node-value project :editor-extensions)
            env (luart/make-env (g/node-value project :workspace))
            library-prototypes (case kind
                                 (:library :all) (g/node-value extensions :library-prototypes)
                                 (:library-prototypes ext-map))
            project-prototypes (case kind
                                 (:project :all) (g/node-value extensions :project-prototypes)
                                 (:project-prototypes ext-map))]))))



#_(let [es-id (first (filter #(g/node-instance? EditorExtensions %) (g/node-ids (g/graph 1))))]
    (g/node-value es-id :project-prototypes))

#_(g/clear-system-cache!)

(defn execute [project step opts]

  #_(let [^LuaValue lua-opts (luart/clj->lua opts)]
      (doseq [^LuaFunction f (get (g/node-value (g/node-value project :editor-extensions) :extensions) step)]
        (try
          (.call f lua-opts)
          (catch LuaError e
            (doseq [l (string/split-lines  (.getMessage e))]
              (console/append-console-entry! :extension-err (str "ERROR:EXT: "l))))))))
