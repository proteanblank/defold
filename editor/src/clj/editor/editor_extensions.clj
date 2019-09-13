(ns editor.editor-extensions
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.luart :as luart]
            [editor.console :as console]
            [clojure.string :as string]
            [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [editor.code.data :as data]
            [editor.resource :as resource]
            [editor.handler :as handler]
            [editor.defold-project :as project]
            [editor.error-reporting :as error-reporting])
  (:import [org.luaj.vm2 LuaFunction LuaValue LuaError Prototype]))

(set! *warn-on-reflection* true)

(g/defnode EditorExtensions
  (input project-prototypes g/Any :array :substitute gu/array-subst-remove-errors)
  (input library-prototypes g/Any :array :substitute gu/array-subst-remove-errors)
  (output project-prototypes g/Any (gu/passthrough project-prototypes))
  (output library-prototypes g/Any (gu/passthrough library-prototypes)))

(defn make [graph]
  (first (g/tx-nodes-added (g/transact (g/make-node graph EditorExtensions)))))

(defprotocol UI
  (reload-resources! [this]
    "Synchronously reload resources, throw exception if failed")
  (can-execute? [this command]
    "Ask user to execute command (a vector of command name string and argument strings)"))

(defn- add-entry [m k v]
  (update m k (fnil conj []) v))

(defn- lua-fn? [x]
  (instance? LuaFunction x))

(s/def :ext-module/get-commands lua-fn?)
(s/def :ext-module/on-build-started lua-fn?)
(s/def :ext-module/on-build-successful lua-fn?)
(s/def :ext-module/on-build-failed lua-fn?)
(s/def :ext-module/on-bundle-started lua-fn?)
(s/def :ext-module/on-bundle-successful lua-fn?)
(s/def :ext-module/on-bundle-failed lua-fn?)
(s/def ::module
  (s/keys :opt-un [:ext-module/get-commands
                   :ext-module/on-build-started
                   :ext-module/on-build-successful
                   :ext-module/on-build-failed
                   :ext-module/on-bundle-started
                   :ext-module/on-bundle-successful
                   :ext-module/on-bundle-failed]))

(s/def :ext-action/action #{"set" "shell"})
(s/def :ext-action/node-id int?)
(s/def :ext-action/property #{"text"})
(s/def :ext-action/value any?)
(s/def :ext-action/command (s/coll-of string?))
(defmulti action-spec :action)
(defmethod action-spec "set" [_]
  (s/keys :req-un [:ext-action/action
                   :ext-action/node-id
                   :ext-action/property
                   :ext-action/value]))
(defmethod action-spec "shell" [_]
  (s/keys :req-un [:ext-action/action
                   :ext-action/command]))
(s/def ::action (s/multi-spec action-spec :action))
(s/def ::actions (s/coll-of ::action))

(s/def :ext-command/label string?)
(s/def :ext-command/locations (s/coll-of #{"Edit" "View" "Assets" "Outline"} :distinct true :min-count 1))
(s/def :ext-command/type #{"resource"})
(s/def :ext-command/cardinality #{"one" "many"})
(s/def :ext-command/selection (s/keys :req-un [:ext-command/type :ext-command/cardinality]))
(s/def :ext-command/query (s/keys :opt-un [:ext-command/selection]))
(s/def :ext-command/active lua-fn?)
(s/def :ext-command/run lua-fn?)
(s/def ::command (s/keys :req-un [:ext-command/label
                                  :ext-command/locations]
                         :opt-un [:ext-command/query
                                  :ext-command/active
                                  :ext-command/run]))

(defn- re-create-ext-map [state env]
  (assoc state :ext-map
               (reduce
                 (fn [acc ^Prototype proto]
                   (let [module (luart/lua->clj (luart/eval proto env))]
                     (if (s/valid? ::module module)
                       (-> acc
                           (update :all #(reduce-kv add-entry % module))
                           (cond-> (= "/hooks.editor_script" (.tojstring (.-source proto)))
                                   (assoc :hooks module)))
                       (do
                         (doseq [line (string/split-lines (s/explain-str ::module module))]
                           (console/append-console-entry! :extension-err line))
                         acc))))
                 {}
                 (concat (:library-prototypes state)
                         (:project-prototypes state)))))

(def ^:private ^:dynamic *execution-context*
  "A map with `:project-id` and `:evaluation-context`"
  nil)

(defn- node-id->type-keyword [node-id ec]
  (let [{:keys [basis]} ec]
    (:k (g/node-type basis (g/node-by-id basis node-id)))))

(defmulti ext-get (fn [node-id key ec]
                    [(node-id->type-keyword node-id ec) key]))

(defmethod ext-get [:editor.code.resource/CodeEditorResourceNode "text"] [node-id _ ec]
  (clojure.string/join \newline (g/node-value node-id :lines ec)))

(defmethod ext-get [:editor.resource/ResourceNode "path"] [node-id _ ec]
  (resource/resource->proj-path (g/node-value node-id :resource ec)))

(defn- do-ext-get [node-id key]
  (let [{:keys [evaluation-context]} *execution-context*]
    (ext-get node-id key evaluation-context)))

(defn- transact! [txs _execution-context]
  (g/transact txs))

(defn- shell! [commands execution-context]
  (let [{:keys [evaluation-context project ui]} execution-context
        root (-> project
                 (g/node-value :workspace evaluation-context)
                 (g/node-value :root evaluation-context))]
    (doseq [cmd+args commands]
      (if (can-execute? ui cmd+args)
        (apply shell/sh (concat cmd+args [:dir root]))
        (throw (ex-info (str "Command `" (string/join " " cmd+args) "` aborted") {:cmd cmd+args}))))
    (reload-resources! ui)))

(defmulti transaction-action->txs (fn [action evaluation-context]
                                    [(:action action)
                                     (node-id->type-keyword (:node-id action) evaluation-context)
                                     (:property action)]))

(defmethod transaction-action->txs ["set" :editor.code.resource/CodeEditorResourceNode "text"]
  [action _]
  (let [node-id (:node-id action)
        value (:value action)]
    [(g/set-property node-id :modified-lines (string/split value #"\n"))
     (g/update-property node-id :invalidated-rows conj 0)
     (g/set-property node-id :cursor-ranges [#code/range[[0 0] [0 0]]])
     (g/set-property node-id :regions [])]))

(defmulti action->batched-executor+input (fn [action _evaluation-context]
                                           (:action action)))

(defmethod action->batched-executor+input "set" [action evaluation-context]
  [transact! (transaction-action->txs action evaluation-context)])

(defmethod action->batched-executor+input "shell" [action _]
  [shell! (:command action)])

(defn perform-actions! [extension-actions execution-context]
  (if-not (s/valid? ::actions extension-actions)
    (doseq [line (string/split-lines (s/explain-str ::actions extension-actions))]
      (console/append-console-entry! :extension-err line))
    (let [{:keys [evaluation-context]} execution-context]
      (doseq [[executor inputs] (eduction (map #(action->batched-executor+input % evaluation-context))
                                          (partition-by first)
                                          (map (juxt ffirst #(mapv second %)))
                                          extension-actions)]
        (executor inputs execution-context)))))

(defmacro with-execution-context [project-expr ui-expr ec-expr & body]
  `(binding [*execution-context* {:project ~project-expr
                                  :evaluation-context ~ec-expr
                                  :ui ~ui-expr}]
     ~@body))

(defmacro with-auto-execution-context [project-expr ui-expr & body]
  `(g/with-auto-evaluation-context ec#
     (with-execution-context ~project-expr ~ui-expr ec# ~@body)))

(defn- exec-all [project ui fn-name opts]
  (with-auto-execution-context project ui
    (let [^LuaValue lua-opts (luart/clj->lua opts)]
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
                (get-in [:ext-map :all fn-name]))))))

(defn- exec-hook [project ui hook opts]
  (with-auto-execution-context project ui
    (let [^LuaValue lua-opts (luart/clj->lua opts)]
      (when-let [^LuaFunction f (-> project
                                    (g/node-value :editor-extensions)
                                    (g/user-data :state)
                                    (get-in [:ext-map :hooks hook]))]
        (try
          (some-> (luart/lua->clj (.call f lua-opts))
                  (perform-actions! *execution-context*))
          (catch Exception e
            (doseq [line (string/split-lines (.getMessage e))]
              (console/append-console-entry! :extension-err (str "ERROR:EXT: "
                                                                 (string/replace (name hook) "-" "_")
                                                                 " failed: "
                                                                 (.getMessage e))))))))))

(defn- continue [acc env lua-fn f & args]
  (let [new-lua-fn (fn [env m]
                     (lua-fn env (apply f m args)))]
    ((acc new-lua-fn) env)))

(defmacro gen-query [[env-sym cont-sym] acc-sym & body-expr]
  `(fn [lua-fn#]
     (fn [~env-sym]
       (let [~cont-sym (partial continue ~acc-sym ~env-sym lua-fn#)]
         ~@body-expr))))

(defmulti gen-selection-query (fn [q acc project]
                                (:type q)))

(defn- ensure-selection-cardinality [selection q]
  (if (= "one" (:cardinality q))
    (when (= 1 (count selection))
      (first selection))
    selection))

(defn- node-ids->lua-selection [selection q]
  (ensure-selection-cardinality (mapv luart/wrap-user-data selection) q))

(defmethod gen-selection-query "resource" [q acc project]
  (gen-query [env cont] acc
    (let [evaluation-context (or (:evaluation-context env)
                                 (g/make-evaluation-context))
          selection (:selection env)]
      (when-let [res (or (some-> selection
                                 (handler/adapt-every resource/ResourceNode)
                                 (node-ids->lua-selection q))
                         (some-> selection
                                 (handler/adapt-every resource/Resource)
                                 (->> (keep #(project/get-resource-node project % evaluation-context)))
                                 (node-ids->lua-selection q)))]
        (cont assoc :selection res)))))

(defn- compile-query [q project]
  (reduce-kv
    (fn [acc k v]
      (case k
        :selection (gen-selection-query v acc project)
        acc))
    (fn [lua-fn]
      (fn [env]
        (lua-fn env {})))
    q))

(defn- lua-command->dynamic-command [{:keys [label query active run locations] :as command} project ui]
  (if (s/valid? ::command command)
    (let [lua-fn->env-fn (compile-query query project)
          contexts (into #{}
                         (map {"Assets" :asset-browser
                               "Outline" :outline
                               "Edit" :global
                               "View" :global})
                         locations)
          locations (into #{}
                          (map {"Assets" :editor.asset-browser/context-menu-end
                                "Outline" :editor.outline-view/context-menu-end
                                "Edit" :editor.app-view/edit-end
                                "View" :editor.app-view/view-end})
                          locations)]
      {:context-definition contexts
       :menu-item {:label label}
       :locations locations
       :fns (cond-> {}
                    active
                    (assoc :active?
                           (lua-fn->env-fn
                             (fn [env opts]
                               (with-execution-context project ui (:evaluation-context env)
                                 (luart/lua->clj (luart/invoke active (luart/clj->lua opts)))))))
                    run
                    (assoc :run
                           (lua-fn->env-fn
                             (fn [_ opts]
                               (with-auto-execution-context project ui
                                 (future
                                   (error-reporting/catch-all!
                                     (try
                                       (some-> (luart/lua->clj (luart/invoke run (luart/clj->lua opts)))
                                               (perform-actions! *execution-context*))
                                       (catch Exception e
                                         (console/append-console-entry! :extension-err (str "ERROR:EXT: " label " failed: " (.getMessage e)))))))
                                 nil)))))})
    (do
      (doseq [line (string/split-lines (s/explain-str ::command command))]
        (console/append-console-entry! :extension-err line))
      nil)))

(defn- reload-commands! [project ui]
  (let [commands (into []
                       (comp
                         cat
                         (keep #(lua-command->dynamic-command % project ui)))
                       (exec-all project ui :get-commands {}))]
    (handler/register-dynamic! ::commands commands)))

(defn reload [project kind ui]
  (g/with-auto-evaluation-context ec
    (g/user-data-swap!
      (g/node-value project :editor-extensions ec)
      :state
      (fn [state]
        (let [extensions (g/node-value project :editor-extensions ec)
              env (luart/make-env (fn [path]
                                    (some-> (project/get-resource-node project path ec)
                                            (g/node-value :lines ec)
                                            (data/lines-input-stream)))
                                  {"editor" {"get" do-ext-get}})]
          (-> state
              (assoc :ui ui)
              (cond-> (#{:library :all} kind)
                      (assoc :library-prototypes
                             (g/node-value extensions :library-prototypes ec))

                      (#{:project :all} kind)
                      (assoc :project-prototypes
                             (g/node-value extensions :project-prototypes ec)))
              (re-create-ext-map env))))))
  (reload-commands! project ui))
