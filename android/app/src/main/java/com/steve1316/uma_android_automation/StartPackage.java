package com.steve1316.uma_android_automation;

import androidx.annotation.NonNull;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;

import com.steve1316.uma_android_automation.bot.solver.SmartRaceSolverModule;
import com.steve1316.uma_android_automation.llm.LLMChatModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StartPackage implements ReactPackage {
    @NonNull
    @Override
    public List<ViewManager> createViewManagers(@NonNull ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<NativeModule> createNativeModules(@NonNull ReactApplicationContext reactContext) {
        List<NativeModule> modules = new ArrayList<>();

        modules.add(new StartModule(reactContext));
        modules.add(new LLMChatModule(reactContext));
        modules.add(new SmartRaceSolverModule(reactContext));
        modules.add(new StorageBridgeModule(reactContext));
        modules.add(new LogcatModule(reactContext));
        modules.add(new DevMcpModule(reactContext));

        return modules;
    }
}