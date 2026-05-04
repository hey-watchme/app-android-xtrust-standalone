package com.xtrust.bonsai.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;

public final class BonsaiNativeBridge implements AutoCloseable {
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a concise assistant. Reply in the same language as the user.";

    static {
        System.loadLibrary("bonsai-runtime");
    }

    private final String nativeLibDir;
    private boolean backendInitialized = false;
    private boolean modelLoaded = false;

    public BonsaiNativeBridge(Context context) {
        this.nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        initBackendOnce();
    }

    public synchronized void loadModel(String modelPath) throws IOException {
        loadModel(modelPath, DEFAULT_SYSTEM_PROMPT);
    }

    public synchronized void loadModel(String modelPath, String systemPrompt) throws IOException {
        initBackendOnce();
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new IOException("Model file not found: " + modelPath);
        }
        if (!modelFile.canRead()) {
            throw new IOException("Model file is not readable: " + modelPath);
        }

        if (modelLoaded) {
            unloadModel();
        }

        if (load(modelPath) != 0) {
            throw new IOException("Failed to load model: " + modelPath);
        }
        if (prepare() != 0) {
            unload();
            throw new IOException("Failed to prepare llama context");
        }
        if (processSystemPrompt(systemPrompt) != 0) {
            unload();
            throw new IOException("Failed to apply system prompt");
        }
        modelLoaded = true;
    }

    public synchronized String complete(String userPrompt, int predictLength) throws IOException {
        ensureModelLoaded();
        if (processUserPrompt(userPrompt, predictLength) != 0) {
            throw new IOException("Failed to process user prompt");
        }

        StringBuilder builder = new StringBuilder();
        while (true) {
            String token = generateNextToken();
            if (token == null) {
                break;
            }
            builder.append(token);
        }
        return builder.toString().trim();
    }

    public synchronized void resetConversation(String systemPrompt) throws IOException {
        ensureModelLoaded();
        if (processSystemPrompt(systemPrompt) != 0) {
            throw new IOException("Failed to reset system prompt");
        }
    }

    public synchronized String bench(int promptTokens, int generationBatches, int parallelTokens, int runs)
            throws IOException {
        ensureModelLoaded();
        return benchModel(promptTokens, generationBatches, parallelTokens, runs);
    }

    public synchronized String systemInfoText() {
        return systemInfo();
    }

    public synchronized void unloadModel() {
        if (!modelLoaded) {
            return;
        }
        unload();
        modelLoaded = false;
    }

    @Override
    public synchronized void close() {
        unloadModel();
        if (backendInitialized) {
            shutdown();
            backendInitialized = false;
        }
    }

    private void initBackendOnce() {
        if (backendInitialized) {
            return;
        }
        init(nativeLibDir);
        backendInitialized = true;
    }

    private void ensureModelLoaded() throws IOException {
        if (!modelLoaded) {
            throw new IOException("Model is not loaded");
        }
    }

    private native void init(String nativeLibDir);
    private native int load(String modelPath);
    private native int prepare();
    private native String systemInfo();
    private native String benchModel(int pp, int tg, int pl, int nr);
    private native int processSystemPrompt(String systemPrompt);
    private native int processUserPrompt(String userPrompt, int predictLength);
    private native String generateNextToken();
    private native void unload();
    private native void shutdown();
}
