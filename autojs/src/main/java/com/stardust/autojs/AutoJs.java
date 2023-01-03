package com.stardust.autojs;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.stardust.app.OnActivityResultDelegate;
import com.stardust.app.SimpleActivityLifecycleCallbacks;
import com.stardust.autojs.core.accessibility.AccessibilityBridge;
import com.stardust.autojs.core.activity.ActivityInfoProvider;
import com.stardust.autojs.core.console.ConsoleImpl;
import com.stardust.autojs.core.console.GlobalConsole;
import com.stardust.autojs.core.image.capture.CaptureForegroundService;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequestActivity;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester;
import com.stardust.autojs.core.record.accessibility.AccessibilityActionRecorder;
import com.stardust.autojs.core.util.Shell;
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine;
import com.stardust.autojs.engine.RootAutomatorEngine;
import com.stardust.autojs.engine.ScriptEngineManager;
import com.stardust.autojs.rhino.InterruptibleAndroidContextFactory;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig;
import com.stardust.autojs.runtime.api.AppUtils;
import com.stardust.autojs.script.AutoFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.pio.PFiles;
import com.stardust.util.ResourceMonitor;
import com.stardust.util.ScreenMetrics;
import com.stardust.util.UiHandler;
import com.stardust.view.accessibility.AccessibilityNotificationObserver;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.LayoutInspector;

import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.WrappedException;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import de.mindpipe.android.logging.log4j.LogConfigurator;

/**
 * Created by Stardust on 2017/11/29.
 */

public abstract class AutoJs {

    private final AccessibilityActionRecorder mAccessibilityActionRecorder = new AccessibilityActionRecorder();
    private final AccessibilityNotificationObserver mNotificationObserver;
    private ScriptEngineManager mScriptEngineManager;
    private final LayoutInspector mLayoutInspector;
    private final Context mContext;
    private final Application mApplication;
    private final UiHandler mUiHandler;
    private final AppUtils mAppUtils;
    private final ActivityInfoProvider mActivityInfoProvider;
    private final ScreenCaptureRequester mScreenCaptureRequester = new ScreenCaptureRequesterImpl();
    private final ScriptEngineService mScriptEngineService;
    private final GlobalConsole mGlobalConsole;


    protected AutoJs(final Application application) {
        mContext = application.getApplicationContext();
        mApplication = application;
        mLayoutInspector = new LayoutInspector(mContext);
        mUiHandler = new UiHandler(mContext);
        mAppUtils = createAppUtils(mContext);
        mGlobalConsole = createGlobalConsole();
        mNotificationObserver = new AccessibilityNotificationObserver(mContext);
        mScriptEngineService = buildScriptEngineService();
        mActivityInfoProvider = new ActivityInfoProvider(mContext, mScriptEngineManager);
        ScriptEngineService.setInstance(mScriptEngineService);
        init();
    }

    protected AppUtils createAppUtils(Context context) {
        return new AppUtils(mContext);
    }

    protected GlobalConsole createGlobalConsole() {
        return new GlobalConsole(mUiHandler);
    }

    protected void init() {
        addAccessibilityServiceDelegates();
        registerActivityLifecycleCallbacks();
        ResourceMonitor.setExceptionCreator(resource -> {
            Exception exception;
            if (org.mozilla.javascript.Context.getCurrentContext() != null) {
                exception = new WrappedException(new ResourceMonitor.UnclosedResourceException(resource));
            } else {
                exception = new ResourceMonitor.UnclosedResourceException(resource);
            }
            exception.fillInStackTrace();
            return exception;
        });
        ResourceMonitor.setUnclosedResourceDetectedHandler(mGlobalConsole::error);
    }

    public abstract void ensureAccessibilityServiceEnabled();

    protected Application getApplication() {
        return mApplication;
    }

    public ScriptEngineManager getScriptEngineManager() {
        return mScriptEngineManager;
    }

    protected ScriptEngineService buildScriptEngineService() {
        initScriptEngineManager();
        return new ScriptEngineServiceBuilder()
                .uiHandler(mUiHandler)
                .globalConsole(mGlobalConsole)
                .engineManger(mScriptEngineManager)
                .build();
    }

    protected void initScriptEngineManager() {
        mScriptEngineManager = new ScriptEngineManager(mContext);
        mScriptEngineManager.registerEngine(JavaScriptSource.ENGINE, () -> {
            LoopBasedJavaScriptEngine engine = new LoopBasedJavaScriptEngine(mContext);
            engine.setRuntime(createRuntime());
            return engine;
        });
        initContextFactory();
        mScriptEngineManager.registerEngine(AutoFileSource.ENGINE, () -> new RootAutomatorEngine(mContext));
    }

    protected void initContextFactory() {
        ContextFactory.initGlobal(new InterruptibleAndroidContextFactory(new File(mContext.getCacheDir(), "classes")));
    }

    protected ScriptRuntime createRuntime() {
        ScriptRuntime runtime = new ScriptRuntime.Builder()
                .setConsole(new ConsoleImpl(mUiHandler, mGlobalConsole))
                .setScreenCaptureRequester(mScreenCaptureRequester)
                .setAccessibilityBridge(new AccessibilityBridgeImpl(mUiHandler))
                .setUiHandler(mUiHandler)
                .setAppUtils(mAppUtils)
                .setEngineService(mScriptEngineService)
                .setShellSupplier(() -> new Shell(mContext, true)).build();
        runtime.putProperty("func.clear-accessibility-cache", new ClearCache(runtime));
        return runtime;
    }

    protected void registerActivityLifecycleCallbacks() {
        getApplication().registerActivityLifecycleCallbacks(new SimpleActivityLifecycleCallbacks() {

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                ScreenMetrics.initIfNeeded(activity);
                mAppUtils.setCurrentActivity(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                mAppUtils.setCurrentActivity(null);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                ScreenMetrics.initIfNeeded(activity);
                mAppUtils.setCurrentActivity(activity);
            }
        });
    }


    private void addAccessibilityServiceDelegates() {
        AccessibilityService.Companion.addDelegate(100, mActivityInfoProvider);
        AccessibilityService.Companion.addDelegate(200, mNotificationObserver);
        AccessibilityService.Companion.addDelegate(300, mAccessibilityActionRecorder);
    }

    public AccessibilityActionRecorder getAccessibilityActionRecorder() {
        return mAccessibilityActionRecorder;
    }

    public AppUtils getAppUtils() {
        return mAppUtils;
    }

    public UiHandler getUiHandler() {
        return mUiHandler;
    }

    public LayoutInspector getLayoutInspector() {
        return mLayoutInspector;
    }

    public GlobalConsole getGlobalConsole() {
        return mGlobalConsole;
    }

    public ScriptEngineService getScriptEngineService() {
        return mScriptEngineService;
    }

    public ActivityInfoProvider getInfoProvider() {
        return mActivityInfoProvider;
    }


    public abstract void waitForAccessibilityServiceEnabled();

    protected AccessibilityConfig createAccessibilityConfig() {
        return new AccessibilityConfig();
    }

    private class AccessibilityBridgeImpl extends AccessibilityBridge {

        public AccessibilityBridgeImpl(UiHandler uiHandler) {
            super(mContext, createAccessibilityConfig(), uiHandler);
        }

        @Override
        public void ensureServiceEnabled() {
            AutoJs.this.ensureAccessibilityServiceEnabled();
        }

        @Override
        public void waitForServiceEnabled() {
            AutoJs.this.waitForAccessibilityServiceEnabled();
        }

        @Nullable
        @Override
        public AccessibilityService getService() {
            return AccessibilityService.Companion.getInstance();
        }

        @Override
        public ActivityInfoProvider getInfoProvider() {
            return mActivityInfoProvider;
        }

        @Override
        public AccessibilityNotificationObserver getNotificationObserver() {
            return mNotificationObserver;
        }

    }

    private class ScreenCaptureRequesterImpl extends ScreenCaptureRequester.AbstractScreenCaptureRequester {

        @Override
        public void setOnActivityResultCallback(Callback callback) {
            super.setOnActivityResultCallback((result, data) -> {
                mResult = data;
                callback.onRequestResult(result, data);
            });
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void request() {
            Activity activity = mAppUtils.getCurrentActivity();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mContext.startForegroundService(new Intent(mContext, CaptureForegroundService.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            if (activity instanceof OnActivityResultDelegate.DelegateHost) {
                ScreenCaptureRequester requester = new ActivityScreenCaptureRequester(
                        ((OnActivityResultDelegate.DelegateHost) activity).getOnActivityResultDelegateMediator(), activity);
                requester.setOnActivityResultCallback(mCallback);
                requester.request();
            } else {
                ScreenCaptureRequestActivity.request(mContext, mCallback);
            }
        }
    }

    public void setLogFilePath(String path, boolean isDebug) {
        LogConfigurator logConfigurator = new LogConfigurator();
        String pid = String.valueOf(android.os.Process.myPid());
        logConfigurator.setFilePattern("%d - [%p]\t[" + pid + "-%t] %c - %m%n");
        String logPath = path + "/.logs/";
        File logDir = new File(logPath);
        if (!logDir.exists() && !logDir.mkdirs()) {
            Log.d("LOG4J-CONFIG", "创建日志目录失败，无法记录log4j日志");
            return;
        }
        if (!logDir.isDirectory()) {
            Log.d("LOG4J-CONFIG", "日志目录不是文件夹，无法记录log4j日志");
            return;
        }
        String logFileName = path + "/.logs/autojs-log4j" + (isDebug ? "-debug" : "") + ".txt";
        try {
            PFiles.append(logFileName, "");
        } catch (Exception e) {
            Log.d("LOG4J-CONFIG", "日志文件无写入权限，无法记录log4j日志");
            return;
        }
        logConfigurator.setFileName(logFileName);
        // 设置最大10MB
        logConfigurator.setMaxFileSize(10 * 1024 * 1024);
        try {
            logConfigurator.configure();
        } catch (Exception e) {
            Log.d("LOG4J-CONFIG", "初始化log4j失败");
        }
    }

    /**
     * 虽然很不要脸的抄袭的pro 但是反编译后发现是空的 很尴尬
     */
    private final class ClearCache implements Runnable {
        private ScriptRuntime runtime;

        public ClearCache(ScriptRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public void run() {
            runtime.getAccessibilityBridge().clearCache();
        }
    }
}
