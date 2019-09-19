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
  (:import [org.luaj.vm2 LuaFunction LuaError Prototype LuaValue]))

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
(s/def ::actions (s/coll-of ::action :kind vector?))

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

(defn- ext-state [project]
  (-> project
      (g/node-value :editor-extensions)
      (g/user-data :state)))

(def ^:private ^:dynamic *execution-context*
  "A map with `:project-id` and `:evaluation-context`"
  nil)

(defn- execute-with!
  "Executes function passing it an ext-map on an agent thread, returns a future
  with result of that function execution"
  ([project f]
   (execute-with! project {} f))
  ([project options f]
   (let [result-promise (promise)
         state (or (:state options) (ext-state project))]
     (send (:ext-agent state)
           (fn [ext-map]
             (let [evaluation-context (or (:evaluation-context options) (g/make-evaluation-context))]
               (binding [*execution-context* {:project project
                                              :evaluation-context evaluation-context
                                              :ui (:ui state)}]
                 (result-promise (try
                                   [nil (f ext-map)]
                                   (catch Exception e
                                     [e nil])))
                 (when-not (contains? options :evaluation-context)
                   (g/update-cache-from-evaluation-context! evaluation-context))))
             ext-map))
     (future (let [[err ret] @result-promise]
               (if err
                 (throw err)
                 ret))))))

(defn- report-extension-error [label ^Exception ex]
  (let [message (str "ERROR:EXT: "
                     label " failed: \n"
                     (if-let [problems (::s/problems (ex-data ex))]
                       (string/join "\n" (->> problems
                                              (map #(str (:val %) " is not " (:pred %)))))
                       (.getMessage ex)))]
    (doseq [line (string/split-lines message)]
      (console/append-console-entry! :extension-err line))))

(defmacro try-with-extension-exceptions [label-expr & body]
  `(try
     ~@body
     (catch Exception e#
       (report-extension-error ~label-expr e#)
       (throw e#))))

(defmacro ignore-exceptions [& body]
  `(try ~@body (catch Exception ~'_)))

(defn- execute-all-top-level-functions! [project state fn-keyword opts]
  (execute-with! project {:state state}
    (fn [ext-map]
      (let [lua-opts (luart/clj->lua opts)]
        (into []
              (keep
                (fn [^LuaValue f]
                  (ignore-exceptions
                    (try-with-extension-exceptions fn-keyword
                      (luart/lua->clj (luart/invoke f lua-opts))))))
              (get-in ext-map [:all fn-keyword]))))))

(defn- re-create-ext-agent [state env]
  (assoc state :ext-agent
               (agent (reduce
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
                                (:project-prototypes state)))
                      :error-handler (fn [_ ex]
                                       (error-reporting/report-exception! ex)))))

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

(defn- ensure-spec [spec x]
  (if (s/valid? spec x)
    x
    (throw (ex-info "Spec assertion failed" (s/explain-data spec x)))))

(defn- perform-actions! [actions execution-context]
  (let [{:keys [evaluation-context]} execution-context]
    (doseq [[executor inputs] (eduction (map #(action->batched-executor+input % evaluation-context))
                                        (partition-by first)
                                        (map (juxt ffirst #(mapv second %)))
                                        (ensure-spec ::actions actions))]
      (executor inputs execution-context))))

(defn execute-hook! [project hook-keyword opts]
  (when-let [state (ext-state project)]
    @(execute-with! project {:state state}
       (fn [ext-map]
         (some-> (get-in ext-map [:hooks hook-keyword])
                 (as-> lua-fn
                       (luart/lua->clj (luart/invoke lua-fn (luart/clj->lua opts))))
                 (perform-actions! #_(str "hook `" (string/replace (name hook-keyword) "-" "_") "`")
                                   *execution-context*))))))

(defn- continue [acc env lua-fn f & args]
  (let [new-lua-fn (fn [env m]
                     (lua-fn env (apply f m args)))]
    ((acc new-lua-fn) env)))

(defmacro gen-query [acc-sym [env-sym cont-sym] & body-expr]
  `(fn [lua-fn#]
     (fn [~env-sym]
       (let [~cont-sym (partial continue ~acc-sym ~env-sym lua-fn#)]
         ~@body-expr))))

(defmulti gen-selection-query (fn [q _acc _project]
                                (:type q)))

(defn- ensure-selection-cardinality [selection q]
  (if (= "one" (:cardinality q))
    (when (= 1 (count selection))
      (first selection))
    selection))

(defn- node-ids->lua-selection [selection q]
  (ensure-selection-cardinality (mapv luart/wrap-user-data selection) q))

(defmethod gen-selection-query "resource" [q acc project]
  (gen-query acc [env cont]
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

(defn- lua-command->dynamic-command [{:keys [label query active run locations] :as command} project state]
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
                               (-> project
                                   (execute-with!
                                     {:state state
                                      :evaluation-context (:evaluation-context env)}
                                     (fn [_]
                                       (luart/lua->clj (luart/invoke active (luart/clj->lua opts)))))
                                   (deref 100 false)))))
                    run
                    (assoc :run
                           (lua-fn->env-fn
                             (fn [_ opts]
                               (execute-with! project {:state state}
                                 (fn [_]
                                   (when-let [actions (luart/lua->clj (luart/invoke run (luart/clj->lua opts)))]
                                     (perform-actions! actions #_label *execution-context*))))))))})
    (do
      (doseq [line (string/split-lines (s/explain-str ::command command))]
        (console/append-console-entry! :extension-err line))
      nil)))

(defn- reload-commands! [project]
  (let [state (ext-state project)
        commands (into []
                       (comp
                         cat
                         (keep #(lua-command->dynamic-command % project state)))
                       @(execute-all-top-level-functions! project state :get-commands {}))]
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
              (re-create-ext-agent env))))))
  (reload-commands! project))
