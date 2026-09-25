package com.example.bitcoinjwallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WalletManager {
    public interface Listener {
        void onWalletReady(Wallet wallet);
        void onProgress(double progress);
        void onError(Throwable error);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WalletAppKit kit;
    private BitcoinNetwork network = BitcoinNetwork.TESTNET;
    private final AtomicBoolean starting = new AtomicBoolean();

    public WalletManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(BitcoinNetwork network, Listener listener) {
        if (starting.get() || kit != null) return;
        starting.set(true);
        this.network = network;

        new Thread(() -> {
            try {
                File dir = new File(context.getFilesDir(), "bitcoinj");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Cannot create wallet directory");
                }

                org.bitcoinj.core.Context.propagate(new org.bitcoinj.core.Context());
                kit = new WalletAppKit(
                        network,
                        ScriptType.P2WPKH,
                        KeyChainGroupStructure.BIP43,
                        dir,
                        "bitcoinj-wallet"
                ) {
                    @Override
                    protected void onSetupCompleted() {
                        Wallet wallet = wallet();
                        main.post(() -> listener.onWalletReady(wallet));
                    }
                };

                kit.setUserAgent("BitcoinJAndroidWallet", "1.0.0")
                        .setBlockingStartup(false)
                        .setAutoSave(true)
                        .setDownloadListener(new DownloadProgressTracker() {
                            @Override
                            protected void progress(double pct, int blocksSoFar, java.time.Instant time) {
                                main.post(() -> listener.onProgress(pct));
                            }

                            @Override
                            protected void doneDownload() {
                                main.post(() -> listener.onProgress(1.0));
                            }
                        });

                kit.startAsync();
                starting.set(false);
            } catch (Throwable t) {
                starting.set(false);
                main.post(() -> listener.onError(t));
            }
        }, "bitcoinj-start").start();
    }

    public synchronized void restartFromSeed(DeterministicSeed seed, Listener listener) {
        stop();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> startWithSeed(seed, listener), 150);
    }

    private synchronized void startWithSeed(DeterministicSeed seed, Listener listener) {
        new Thread(() -> {
            try {
                File dir = new File(context.getFilesDir(), "bitcoinj");
                org.bitcoinj.core.Context.propagate(new org.bitcoinj.core.Context());
                kit = new WalletAppKit(
                        network,
                        ScriptType.P2WPKH,
                        KeyChainGroupStructure.BIP43,
                        dir,
                        "bitcoinj-wallet"
                ) {
                    @Override
                    protected void onSetupCompleted() {
                        main.post(() -> listener.onWalletReady(wallet()));
                    }
                };
                kit.restoreWalletFromSeed(seed);
                kit.setUserAgent("BitcoinJAndroidWallet", "1.0.0")
                        .setBlockingStartup(false)
                        .setAutoSave(true)
                        .setDownloadListener(new DownloadProgressTracker() {
                            @Override
                            protected void progress(double pct, int blocksSoFar, java.time.Instant time) {
                                main.post(() -> listener.onProgress(pct));
                            }
                            @Override
                            protected void doneDownload() {
                                main.post(() -> listener.onProgress(1.0));
                            }
                        });
                kit.startAsync();
            } catch (Throwable t) {
                main.post(() -> listener.onError(t));
            }
        }, "bitcoinj-restore").start();
    }

    public synchronized Wallet wallet() {
        return kit == null ? null : kit.wallet();
    }

    public synchronized WalletAppKit kit() {
        return kit;
    }

    public synchronized BitcoinNetwork network() {
        return network;
    }

    public synchronized File dataDir() {
        return new File(context.getFilesDir(), "bitcoinj");
    }

    public synchronized void stop() {
        if (kit != null) {
            try {
                kit.stopAsync();
                kit.awaitTerminated();
            } catch (Throwable ignored) {
            }
            kit = null;
        }
    }
}
