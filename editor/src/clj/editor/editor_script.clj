(ns editor.editor-script
  (:require [dynamo.graph :as g]
            [editor.code.script :as script]
            [editor.code.resource :as r]
            [editor.resource-io :as resource-io]
            [editor.resource :as resource]
            [editor.luart :as luart]
            [clojure.string :as string]
            [schema.core :as s]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.luaj.vm2 LuaFunction Prototype Globals LuaClosure]
           [org.luaj.vm2.compiler LuaC]))

(g/defnode EditorScript
  (inherits r/CodeEditorResourceNode)
  (input globals g/Any)
  (output source-value g/Any :cached (g/fnk [_node-id resource editable?]
                                       ;; todo copy-paste
                                       (when-some [read-fn (:read-fn (resource/resource-type resource))]
                                         (when (and editable? (resource/exists? resource))
                                           (resource-io/with-error-translation resource _node-id :source-value
                                             (read-fn resource))))))
  (output dirty? g/Bool (g/fnk [cleaned-save-value source-value editable?]
                          ;; todo copy-paste
                          (and editable? (some? cleaned-save-value) (not= cleaned-save-value source-value))))
  (output prototype g/Any :cached (g/fnk [_node-id lines resource]
                                    (try
                                      (luart/read (string/join "\n" lines) (resource/resource->proj-path resource))
                                      (catch Exception e
                                        (g/->error _node-id :prototype :fatal e "Could not compile editor extension")))))
  #_(output extension g/Any :cached (g/fnk [_node-id source-value resource]
                                      (try
                                        (let [globals (luart/make-env (resource/workspace resource))
                                              module (luart/evaluate globals
                                                                     (string/join "\n" source-value)
                                                                     (resource/resource->proj-path resource))]
                                          (s/validate {s/Str LuaFunction} (luart/lua->clj module)))
                                        (catch Exception e
                                          (g/->error _node-id :extension :fatal e "Could not load editor extension"))))))

(defn register-resource-types [workspace]
  (r/register-code-resource-type workspace
                                 :ext "editor_script"
                                 :label "Editor Script"
                                 :icon "icons/32/Icons_29-AT-Unknown.png"
                                 :view-types [:code :default]
                                 :view-opts script/lua-code-opts
                                 :node-type EditorScript
                                 :eager-loading? true
                                 :additional-load-fn
                                 (fn [project self resource]
                                   (let [extensions (g/node-value project :editor-extensions)
                                         target (if (resource/file-resource? resource)
                                                  :project-prototypes
                                                  :library-prototypes)]
                                     (g/connect self :prototype extensions target)))))

#_ (require 'dev)

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (for [[label _] (dev/node-labels es-id)]
      [label (g/node-value es-id label)]))

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (.call (get (g/node-value es-id :extension) "on_bundle_complete")
           (editor.luart/clj->lua {:platform "osx"})))

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))
        f (future (.call (get (g/node-value es-id :extension) "fuck_shit_up")))]
    (prn (deref f 1000 ::fuck))
    (future-cancel f)
    (prn (future-done? f))
    (prn (future-cancelled? f)))

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (g/node-value es-id :prototype))

#_(require '[clj-memory-meter.core :as m])

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))
        res (g/node-value es-id :resource)
        proto (.compile LuaC/instance (io/input-stream res) (resource/resource->proj-path res))]
    (.call (get (luart/lua->clj (.call (LuaClosure. proto (luart/make-env 0)))) "on_bundle_complete")
           (luart/clj->lua {:platform "poo"})))

