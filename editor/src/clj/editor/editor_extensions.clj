(ns editor.editor-extensions
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.luart :as luart]
            [editor.console :as console]
            [clojure.string :as string]
            [schema.core :as s]
            [editor.workspace :as workspace])
  (:import [org.luaj.vm2 LuaFunction LuaValue LuaError]))

(set! *warn-on-reflection* true)

(g/defnode EditorExtensions
  (input project-prototypes g/Any :array :substitute gu/array-subst-remove-errors)
  (input library-prototypes g/Any :array :substitute gu/array-subst-remove-errors))

(defn- re-create-ext-map [state env]
  (assoc state :ext-map
               (transduce
                 (comp
                   (keep
                     (fn [prototype]
                       (try
                         (s/validate {s/Str LuaFunction}
                                     (luart/lua->clj (luart/eval prototype env)))
                         (catch Exception e
                           (when-let [message (.getMessage e)]
                             (doseq [line (string/split-lines message)]
                               (console/append-console-entry! :extension-err line)))
                           nil))))
                   cat)
                 (completing
                   (fn [m [k v]]
                     (update m k (fnil conj []) v)))
                 {}
                 (concat (:library-prototypes state)
                         (:project-prototypes state)))))

(defn reload [project kind]
  (prn `reload kind)
  (g/with-auto-evaluation-context ec
    (g/user-data-swap!
      (g/node-value project :editor-extensions ec)
      :state
      (fn [state]
        (let [extensions (g/node-value project :editor-extensions ec)
              workspace (g/node-value project :workspace ec)
              env (luart/make-env #(workspace/find-resource workspace % ec))]
          (cond-> state
                  (#{:library :all} kind)
                  (assoc :library-prototypes
                         (g/node-value extensions :library-prototypes ec))

                  (#{:project :all} kind)
                  (assoc :project-prototypes
                         (g/node-value extensions :project-prototypes ec))

                  :always
                  (re-create-ext-map env)))))))

#_(let [project (first (filter #(g/node-instance? editor.defold-project/Project %) (g/node-ids (g/graph 1))))]
    (reload project :all))

#_(let [project (first (filter #(g/node-instance? editor.defold-project/Project %) (g/node-ids (g/graph 1))))]
    (g/user-data (g/node-value project :editor-extensions) :state))

#_(let [es-id (first (filter #(g/node-instance? EditorExtensions %) (g/node-ids (g/graph 1))))]
    (g/node-value es-id :project-prototypes))

#_(g/clear-system-cache!)

(defn execute [project step opts]
  (let [^LuaValue lua-opts (luart/clj->lua opts)]
    (doseq [^LuaFunction f (-> project
                               (g/node-value :editor-extensions)
                               (g/user-data :state)
                               (get-in [:ext-map step]))]
      (try
        (.call f lua-opts)
        (catch LuaError e
          (doseq [l (string/split-lines (.getMessage e))]
            (console/append-console-entry! :extension-err (str "ERROR:EXT: " l))))))))
