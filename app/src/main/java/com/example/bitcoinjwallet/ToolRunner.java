package com.example.bitcoinjwallet;

import android.content.Context;

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
        void onFinished(int exitCode, String output);
        void onFailed(Throwable error);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ToolRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    public void run(List<String> userArgs, Callback callback) {
        executor.execute(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            try {
                System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

                List<String> args = new ArrayList<>(userArgs);
                String walletPath = new File(
                        context.getFilesDir(), "bitcoinj-wallet.wallet").getAbsolutePath();
                String chainPath = new File(
                        context.getFilesDir(), "bitcoinj-wallet.spvchain").getAbsolutePath();

                boolean hasWallet = false;
                for (String a : args) {
                    if (a.startsWith("--wallet=")) hasWallet = true;
                }
                if (!hasWallet) args.add("--wallet=" + walletPath);

                boolean hasChain = false;
                for (String a : args) {
                    if (a.startsWith("--chain=")) hasChain = true;
                }
                if (!hasChain) args.add("--chain=" + chainPath);

                int code = new CommandLine(new WalletTool()).execute(args.toArray(new String[0]));
                String combined = out.toString(StandardCharsets.UTF_8)
                        + err.toString(StandardCharsets.UTF_8);
                callback.onFinished(code, combined);
            } catch (Throwable t) {
                callback.onFailed(t);
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
