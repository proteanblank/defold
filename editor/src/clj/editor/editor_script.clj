(ns editor.editor-script
  (:require [dynamo.graph :as g]
            [editor.code.script :as script]
            [editor.code.resource :as r]
            [editor.resource-io :as resource-io]
            [editor.resource :as resource]
            [editor.luart :as luart]
            [clojure.string :as string]
            [schema.core :as s])
  (:import [org.luaj.vm2 LuaFunction]
           [org.luaj.vm2.lib.jse JsePlatform]))

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
  (output extension g/Any (g/fnk [_node-id source-value]
                            (try
                              (let [globals (JsePlatform/debugGlobals)
                                    module (.call (.load globals (string/join "\n" source-value)))]
                                (s/validate {s/Str LuaFunction} (luart/lua->clj module)))
                              (catch Exception e
                                (g/->error _node-id :extension :info "Could not load editor extension" e))))))

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
                                 (fn [project self _]
                                   (let [extensions (g/node-value project :editor-extensions)]
                                     (g/connect self :extension extensions :extensions)))))

#_ (require 'dev)
#_ (require 'editor.luart)

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (for [[label _] (dev/node-labels es-id)]
      [label (g/node-value es-id label)]))

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (.call (get (editor.luart/lua->clj (g/node-value es-id :extension)) "on_bundle_complete")
           (editor.luart/clj->lua {:platform "osx"})))

#_(let [es-id (first (filter #(g/node-instance? EditorScript %) (g/node-ids (g/graph 1))))]
    (g/node-value es-id :extension))

