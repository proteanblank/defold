(ns editor.editor-extensions
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.luart :as luart]
            [editor.console :as console]
            [clojure.string :as string]
            [schema.core :as s]
            [editor.workspace :as workspace]
            [editor.resource :as resource])
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

(def ^:private ^:dynamic *execution-context*)

#_(ns-unmap *ns* 'ext-get)

(defn- node-id->type-keyword [node-id ec]
  (let [{:keys [basis]} ec]
    (:k (g/node-type basis (g/node-by-id basis node-id)))))

(defn- node-id-or-path->node-id [node-id-or-path project ec]
  (if (string? node-id-or-path)
    (-> project
        (g/node-value :nodes-by-resource-path ec)
        (get node-id-or-path))
    node-id-or-path))

(defmulti ext-get (fn [node-id key ec]
                    [(node-id->type-keyword node-id ec) key]))

(defmethod ext-get [:editor.code.resource/CodeEditorResourceNode "text"] [node-id _ ec]
  (clojure.string/join \newline (g/node-value node-id :lines ec)))

(defmethod ext-get [:editor.resource/ResourceNode "path"] [node-id _ ec]
  (resource/resource->proj-path (g/node-value node-id :resource ec)))

(defn- do-ext-get [node-id-or-path key]
  (let [{:keys [project ec]} *execution-context*]
    (ext-get (node-id-or-path->node-id node-id-or-path project ec) key ec)))

#_(ns-unmap *ns* 'command->txs)

(defmulti command->txs (fn [command node-id key value ec]
                         [command (node-id->type-keyword node-id ec) key]))

(defmethod command->txs ["set" :editor.code.resource/CodeEditorResourceNode "text"]
  [_ node-id _ value _]
  [(g/set-property node-id :modified-lines (string/split value #"\n"))
   (g/update-property node-id :invalidated-rows conj 0)
   (g/set-property node-id :cursor-ranges [#code/range[[0 0] [0 0]]])
   (g/set-property node-id :regions [])])

(defn- do-command->txs [[command node-id-or-path key value]]
  (let [{:keys [project ec]} *execution-context*]
    (command->txs command
                  (node-id-or-path->node-id node-id-or-path project ec)
                  key
                  value
                  ec)))

(defn reload [project kind]
  (g/with-auto-evaluation-context ec
    (g/user-data-swap!
      (g/node-value project :editor-extensions ec)
      :state
      (fn [state]
        (let [extensions (g/node-value project :editor-extensions ec)
              workspace (g/node-value project :workspace ec)
              env (luart/make-env #(workspace/find-resource workspace % ec)
                                  {"editor" {"get" do-ext-get}})]
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
    (execute project "on_bundle_complete" {:platform "beep"}))

#_(let [es-id (first (filter #(g/node-instance? EditorExtensions %) (g/node-ids (g/graph 1))))]
    (g/node-value es-id :project-prototypes))

#_(g/clear-system-cache!)

(defn execute [project step opts]
  (g/with-auto-evaluation-context ec
    (let [^LuaValue lua-opts (luart/clj->lua opts)]
      (binding [*execution-context* {:project project :ec ec}]
        (let [txs (into []
                        (comp
                          (keep
                            (fn [^LuaFunction f]
                              (try
                                (luart/lua->clj (.call f lua-opts))
                                (catch LuaError e
                                  (doseq [l (string/split-lines (.getMessage e))]
                                    (console/append-console-entry! :extension-err (str "ERROR:EXT: " l)))))))
                          cat
                          (map do-command->txs))
                        (-> project
                            (g/node-value :editor-extensions)
                            (g/user-data :state)
                            (get-in [:ext-map step])))]
          (g/transact txs)
          nil)))))

(defn- exec [project fn-name opts]
  (g/with-auto-evaluation-context ec
    (let [^LuaValue lua-opts (luart/clj->lua opts)]
      (binding [*execution-context* {:project project :ec ec}]
        (into []
              (keep
                (fn [^LuaFunction f]
                  (try
                    (luart/lua->clj (.call f lua-opts))
                    (catch LuaError e
                      (doseq [l (string/split-lines (.getMessage e))]
                        (console/append-console-entry! :extension-err (str "ERROR:EXT: " l)))))))
              (-> project
                  (g/node-value :editor-extensions)
                  (g/user-data :state)
                  (get-in [:ext-map fn-name])))))))

(defn asset-pane-context-menu-items [project resource-path]
  (into []
        (comp
          cat
          (map (fn [[name lua-closure]]
                 {:name name :callback lua-closure})))
        (exec project "get_assets_pane_context_menu_items" {:path resource-path})))


#_(let [project (first (filter #(g/node-instance? editor.defold-project/Project %) (g/node-ids (g/graph 1))))]
    (asset-pane-context-menu-items project "/main/blep.json"))

#_(let [x (first (filter #(g/node-instance? editor.code.resource/CodeEditorResourceNode %) (g/node-ids (g/graph 1))))]
    (dev/node-labels x))

#_(:k (g/node-type (g/node-by-id 72057594037928762)))

#_(for [x (filter #(g/node-instance? editor.collection/CollectionNode %) (g/node-ids (g/graph 1)))]
    [(editor.resource/resource->proj-path (g/node-value x :resource)) x])

#_ (prn (ext-get "/main/blep.lua" "text" (g/make-evaluation-context)))

#_(ext-get 72057594037928762 "path" (g/make-evaluation-context))

#_ (prn (let [project (first (filter #(g/node-instance? editor.defold-project/Project %) (g/node-ids (g/graph 1))))]
          (binding [*execution-context* {:project project :ec (g/make-evaluation-context)}]
            (do-ext-get "/main/blep.lua" "text"))))
