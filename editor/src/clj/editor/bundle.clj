(ns editor.bundle
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.ui :as ui]
            [editor.dialogs :as dialogs]
            [editor.system :as system]
            [editor.handler :as handler])
  (:import [java.io File]
           [javafx.scene Parent Node Scene Group]
           [javafx.stage Stage StageStyle Modality]
           [com.defold.editor.sign Signer]
           [com.google.common.io Files]
           [org.apache.commons.configuration.plist XMLPropertyListConfiguration]))

(set! *warn-on-reflection* true)

(defn- create-fat-ios-engine []
  (let [unpack (System/getProperty "defold.unpack.path")
        engine-armv7 (format "%s/armv7-ios/bin/dmengine" unpack)
        engine-arm64 (format "%s/arm64-ios/bin/dmengine" unpack)
        lipo (format "%s/bin/lipo" unpack)
        engine (File/createTempFile "dmengine" "")]
    ;; TODO: Check that engine-* exists!!!
    (system/exec [lipo "-create" engine-armv7 engine-arm64 "-output" (.getAbsolutePath engine)] {})
    (prn "!!!!!!!!!!!")
    (prn engine-armv7)
    (prn engine-arm64)
    (prn (.getAbsolutePath engine))
    (prn "!!!!!!!!!!!")

    engine))

(defn- extract-entitlement [profile]
  (let [text-profile (File/createTempFile "mobileprovision" ".plist")]
    (prn text-profile)
    (system/exec ["security"  "cms"  "-D"  "-i"  profile "-o"  (.getAbsolutePath text-profile)] {})

    (let [profile-info (XMLPropertyListConfiguration.)
          entitlements-info (XMLPropertyListConfiguration.)
          entitlements (File/createTempFile "entitlement" ".xcent")]
      (with-open [r (io/reader text-profile)]
        (.load profile-info r))
      (.append entitlements-info (.configurationAt profile-info "Entitlements"))
      (.save entitlements-info entitlements)
      (.getAbsolutePath entitlements))))

(defn- sign-ios-app2 [ipa exe identity profile props]
  (let [unpack (System/getProperty "defold.unpack.path")
        codesign (format "%s/bin/codesign" unpack)
        codesign-alloc (format "%s/bin/codesign_allocate" unpack)
        package-dir (Files/createTempDir)
        payload-dir (io/file package-dir "Payload")
        app-dir (io/file payload-dir "Defold.app")
        info (XMLPropertyListConfiguration.)]
    (.mkdirs app-dir)
    (with-open [r (io/reader (io/resource "bundle/ios/Info-dev-app.plist"))]
      (.load info r))
    (doseq [[k v]  props]
      (.setProperty info k v))
    (.save info (io/file app-dir "Info.plist"))

    ;; copy icons
    (doseq [icon ["ios_icon_57.png", "ios_icon_114.png", "ios_icon_72.png", "ios_icon_144.png"]]
      (io/copy (slurp (io/resource (str "icons/ios/" icon))) (io/file app-dir icon)))

    (io/copy (io/file  profile) (io/file app-dir "embedded.mobileprovision"))

    (io/copy (io/file exe) (io/file app-dir "dmengine"))

    (let [entitlements (extract-entitlement profile)
          env {"EMBEDDED_PROFILE_NAME" "embedded.mobileprovision"
               "CODESIGN_ALLOCATE" codesign-alloc}]
      (prn entitlements)
      (system/exec ["codesign" "-f" "-s" identity "--entitlements" entitlements (.getAbsolutePath app-dir)] env))

    (.delete (io/file ipa))
    (system/exec ["zip" "-qr" ipa "Payload"] package-dir {})
    #_(system/exec ["zip" "-qr" ipa "Defold.app"] payload-dir {})
    (prn "!!!" app-dir)

    app-dir))


(prn (extract-entitlement "/Users/chmu/tmp/embedded.mobileprovision"))

(let [unpack (System/getProperty "defold.unpack.path")
      engine-armv7 (format "%s/armv7-ios/bin/dmengine" unpack)
      engine-arm64 (format "%s/arm64-ios/bin/dmengine" unpack)
      lipo (format "%s/bin/lipo" unpack)
      engine (File/createTempFile "dmengine" "")]
  (system/exec [lipo "-create" engine-armv7 engine-arm64 "-output" (.getAbsolutePath engine)] {})
  (prn (sign-ios-app2
        "/tmp/test.ipa"
        engine
        "B42E688DEA1522C70B465E137AA8ACE58AFF692C"
        "/Users/chmu/tmp/embedded.mobileprovision"
        {"CFBundleIdentifier" "chmu.test-sign"
         "CFBundleExecutable" "dmengine"})))


(defn- sign-ios-app [project identity profile]
  (let [settings (g/node-value project :settings)
        w (get settings ["display" "width"] 1)
        h (get settings ["display" "height"] 1)
        orient (if (> w h)
                 {"UISupportedInterfaceOrientations"      "UIInterfaceOrientationLandscapeRight"
                  "UISupportedInterfaceOrientations~ipad" "UIInterfaceOrientationLandscapeRight"}
                 {"UISupportedInterfaceOrientations"      "UIInterfaceOrientationPortrait"
                  "UISupportedInterfaceOrientations~ipad" "UIInterfaceOrientationPortrait"})
        props {"CFBundleDisplayName" (get settings ["project" "title"] "Unnamed")
               "CFBundleExecutable" "dmengine"
               "CFBundleIdentifier" (get settings ["ios" "bundle_identifier"] "dmengine")}
        props (merge props orient)
        engine ^File (create-fat-ios-engine)
        unpack (System/getProperty "defold.unpack.path")
        codesign-alloc (format "%s/bin/codesign_allocate" unpack)]
    (prn "####" identity)
    (let [ipa (.sign (Signer.) identity profile (.getAbsolutePath engine) codesign-alloc props)]
      (.delete engine)
      ipa)))

(handler/defhandler ::sign :dialog
  (enabled? [controls] (and (ui/selection (:identities controls))
                            (.exists (io/file (ui/text (:provisioning-profile controls))))))
  (run [^Stage stage controls project]
    (let [unpack (System/getProperty "defold.unpack.path")
          engine-armv7 (format "%s/armv7-ios/bin/dmengine" unpack)
          engine-arm64 (format "%s/arm64-ios/bin/dmengine" unpack)
          lipo (format "%s/bin/lipo" unpack)
          engine (File/createTempFile "dmengine" "")]
      (system/exec [lipo "-create" engine-armv7 engine-arm64 "-output" (.getAbsolutePath engine)] {})
      (prn "->" engine-armv7 engine-arm64 engine))

    (let [identity (get-in (ui/selection (:identities controls)) [0 0])
          profile (ui/text (:provisioning-profile controls))
          ipa (sign-ios-app project identity profile)]
      (prn "IPA" ipa))

    (prn (get-in (ui/selection (:identities controls)) [0 0])
         (ui/text (:provisioning-profile controls))
         (format "%s/arm64-ios/bin/dmengine" (System/getProperty "defold.unpack.path")))
    (.close stage)))

(handler/defhandler ::select-provisioning-profile :dialog
  (enabled? [] true)
  (run [stage controls]
    (prn "DO SELECT")
    (let [f (ui/choose-file "Selection Provisioning Profile" "Provisioning Profile" ["*.mobileprovision"])]
      (ui/text! (:provisioning-profile controls) f))))

(defn- find-identities []
  (let [re #"\s+\d+\)\s+([0-9A-Z]+)\s+\"(.*?)\""
        lines (.split ^String (second (system/exec ["security" "find-identity" "-v" "-p" "codesigning"] {})) "\n" )]
    (->> lines
         (map #(first (re-seq re %)))
         (remove nil?)
         (map (fn [[_ id name]] [id name])))))

(defn make-sign-dialog [project]
  (let [root ^Parent (ui/load-fxml "sign-dialog.fxml")
        stage (ui/make-stage)
        scene (Scene. root)
        controls (ui/collect-controls root ["identities" "sign" "provisioning-profile" "provisioning-profile-button"])]

    (ui/context! root :dialog {:controls controls :stage stage :project project} nil)
    (ui/cell-factory! (:identities controls) (fn [i] {:text (second i)}))

    (ui/bind-action! (:provisioning-profile-button controls) ::select-provisioning-profile)
    (ui/bind-action! (:sign controls) ::sign)

    (ui/items! (:identities controls) (find-identities))
    (dialogs/observe-focus stage)
    (.initOwner stage (ui/main-stage))
    (ui/title! stage "Sign iOS Application")
    (.initModality stage Modality/NONE)
    (.setScene stage scene)
    (ui/show! stage)
    stage))


#_(with-open [ins (io/input-stream (io/resource "diff.fxml"))]
  (io/copy ins (io/file "/tmp/foobar123")))

#_(ui/run-later (make-sign-dialog nil))
