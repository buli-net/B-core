package com.example.bitcoinjwallet;

import android.content.Context;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.wallettool.WalletTool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import picocli.CommandLine;

public final class ToolRunner {
    public interface Callback {
        void onStarted();
        void onFinished(int exitCode, String output);
        void onFailed(Throwable error);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final WalletManager manager;

    public ToolRunner(Context context, WalletManager manager) {
        this.context = context.getApplicationContext();
        this.manager = manager;
    }

    public void run(List<String> userArgs, BitcoinNetwork network, Callback callback) {
        executor.execute(() -> {
            mainThreadCallback(callback::onStarted);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            try {
                System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

                manager.ensureStorage(network);
                List<String> args = new ArrayList<>(userArgs);
                File walletFile = manager.walletFile(network);
                File chainFile = manager.chainFile(network);
                if (walletFile.getParentFile() != null && !walletFile.getParentFile().exists()
                        && !walletFile.getParentFile().mkdirs()) {
                    throw new IllegalStateException("Cannot create wallet tool directory: " + walletFile.getParentFile());
                }
                addIfMissing(args, "--wallet=", walletFile.getAbsolutePath());
                addIfMissing(args, "--chain=", chainFile.getAbsolutePath());

                int code = new CommandLine(new WalletTool()).execute(args.toArray(new String[0]));
                String stdout = out.toString(StandardCharsets.UTF_8);
                String stderr = err.toString(StandardCharsets.UTF_8);
                StringBuilder combined = new StringBuilder();
                if (!stdout.isEmpty()) combined.append(stdout);
                if (!stderr.isEmpty()) {
                    if (combined.length() > 0 && combined.charAt(combined.length() - 1) != '\n') combined.append('\n');
                    combined.append(stderr);
                }
                mainThreadCallback(() -> callback.onFinished(code, combined.toString()));
            } catch (Throwable t) {
                mainThreadCallback(() -> callback.onFailed(t));
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
        });
    }

    private static void addIfMissing(List<String> args, String prefix, String value) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) return;
        }
        args.add(prefix + value);
    }

    private void mainThreadCallback(Runnable action) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(action);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
