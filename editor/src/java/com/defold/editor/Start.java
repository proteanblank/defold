package com.defold.editor;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.defold.editor.Updater.PendingUpdate;
import com.defold.libs.ResourceUnpacker;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;


public class Start extends Application {

    private static Logger logger = LoggerFactory.getLogger(Start.class);

    private static Start instance;

    public static Start getInstance() {
        return instance;
    }

    public PendingUpdate getPendingUpdate() {
        return this.pendingUpdate.get();
    }

    private AtomicReference<PendingUpdate> pendingUpdate;
    private Timer updateTimer;
    private Updater updater;
    private final int firstUpdateDelay = 1000;
    private final int updateDelay = 60000;

    public Start() throws IOException {
        pendingUpdate = new AtomicReference<>();
        installUpdater();
    }

    private void installUpdater() throws IOException {
        String updateUrl = System.getProperty("defold.update.url");
        if (updateUrl != null && !updateUrl.isEmpty()) {
            logger.debug("automatic updates enabled");
            String resourcesPath = System.getProperty("defold.resourcespath");
            String sha1 = System.getProperty("defold.sha1");
            if (resourcesPath != null && sha1 != null) {
                updater = new Updater(updateUrl, resourcesPath, sha1);
                updateTimer = new Timer();
                updateTimer.schedule(newCheckForUpdateTask(), firstUpdateDelay);
            } else {
                logger.error(String.format("automatic updates could not be enabled with resourcespath='%s' and sha1='%s'", resourcesPath, sha1));
            }
        } else {
            logger.debug(String.format("automatic updates disabled (defold.update.url='%s')", updateUrl));
        }
    }

    private TimerTask newCheckForUpdateTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    logger.debug("checking for updates");
                    PendingUpdate update = updater.check();
                    if (update != null) {
                        pendingUpdate.compareAndSet(null, update);
                    } else {
                        updateTimer.schedule(newCheckForUpdateTask(), updateDelay);
                    }
                } catch (IOException e) {
                    logger.debug("update check failed", e);
                }
            }
        };
    }

    private void initializeNativeLibraries() {
        try {
            // A terrible hack as an attempt to avoid a deadlock when loading native libraries
            // Prism might be loading native libraries at this point, although we kick this loading after the splash has been shown.
            // The current hypothesis is that the splash is "onShown" before the loading has finished and rendering can start.
            // Occular inspection shows the splash as grey for a few frames (1-3?) before filled in with graphics. That grey-time also seems to differ between runs.
            // This is an attempt to make the deadlock less likely to happen and hopefully avoid it altogether. No guarantees.
            Thread.sleep(200);
            ResourceUnpacker.unpackResources();
            ClassLoader parent = ClassLoader.getSystemClassLoader();
            Class<?> glprofile = parent.loadClass("com.jogamp.opengl.GLProfile");
            Method init = glprofile.getMethod("initSingleton");
            init.invoke(null);
        } catch (Throwable t) {
            logger.error("failed to extract native libs", t);
            // NOTE: Really swallow this one? why not System.exit(1);
        }
    }

    private void startEditor(Splash splash) {
        initializeNativeLibraries();

        try {
            IFn require = Clojure.var("clojure.core", "require");

            if (Editor.isDev()) {
                logger.debug("Starting nrepl");
                require.invoke(Clojure.read("editor.debug"));
                Clojure.var("editor.debug", "start-server").invoke();
                logger.debug("nrepl started");
            }

            logger.debug("Requiring editor.boot");
            require.invoke(Clojure.read("editor.boot"));
            logger.debug("Required editor.boot");

            // fix this
            List<String> params = getParameters().getRaw();
            String[] paramsArray = params.toArray(new String[params.size()]);

            javafx.application.Platform.runLater(() -> {
                try {
                    logger.debug("Calling editor.boot/main");
                    IFn main = Clojure.var("editor.boot", "main");

                    splash.close();
                    main.invoke(paramsArray);
                } catch (Throwable t) {
                    logger.error("unable to call editor.boot/main", t);
                }
            });

        } catch (Throwable t) {
            t.printStackTrace();
            String message = (t instanceof InvocationTargetException) ? t.getCause().getMessage() : t.getMessage();
            javafx.application.Platform.runLater(() -> {
                splash.setLaunchError(message);
                splash.setErrorShowing(true);
            });
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        instance = this;

        final Splash splash = new Splash();
        splash.shownProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.booleanValue()) {
                Thread loadingThread = new Thread(() -> {
                    startEditor(splash);
                });
                loadingThread.setName("defold-loader");
                loadingThread.start();
            }
        });
        splash.show();
    }

    @Override
    public void stop() throws Exception {
        // NOTE: We force exit here as it seems like the shutdown process
        // is waiting for all non-daemon threads to terminate, e.g. clojure agent thread
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        initializeLogging();
        Start.launch(args);
    }

    private static void initializeLogging() {
        String defoldLogDir = System.getProperty("defold.log.dir");
        Path logDirectory = defoldLogDir != null ? Paths.get(defoldLogDir) : Editor.getSupportPath();
        if (logDirectory == null) {
            logDirectory = Paths.get(System.getProperty("user.home"));
        }

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        RollingFileAppender appender = new RollingFileAppender();
        appender.setName("FILE");
        appender.setAppend(true);
        appender.setPrudent(true);
        appender.setContext(root.getLoggerContext());

        TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy();
        rollingPolicy.setMaxHistory(30);
        rollingPolicy.setFileNamePattern(logDirectory.resolve("editor2.%d{yyyy-MM-dd}.log").toString());
        rollingPolicy.setTotalSizeCap(FileSize.valueOf("1GB"));
        rollingPolicy.setContext(root.getLoggerContext());
        rollingPolicy.setParent(appender);
        appender.setRollingPolicy(rollingPolicy);
        rollingPolicy.start();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-4relative [%thread] %-5level %logger{35} - %msg%n");
        encoder.setContext(root.getLoggerContext());
        encoder.start();

        appender.setEncoder(encoder);
        appender.start();

        root.addAppender(appender);
    }

}
