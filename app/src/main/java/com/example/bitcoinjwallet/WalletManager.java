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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private BitcoinNetwork network = BitcoinNetwork.MAINNET;
    private double lastProgress = 0.0;
    private boolean syncing = false;

    private File walletDir(BitcoinNetwork n) {
        return new File(context.getFilesDir(), "bitcoinj-" + n.name().toLowerCase());
    }

    private String walletPrefix(BitcoinNetwork n) {
        return "bitcoinj-wallet-" + n.name().toLowerCase();
    }

    private void prepareWalletStorage(BitcoinNetwork n) throws Exception {
        File dir = walletDir(n);
        if (!dir.exists() && !dir.mkdirs())
            throw new IllegalStateException("Cannot create wallet directory: " + dir);

        File target = new File(dir, walletPrefix(n) + ".wallet");
        if (target.exists()) return;

        // Migrate the old single-wallet layout only when its network matches.
        File legacyDir = new File(context.getFilesDir(), "bitcoinj");
        File legacyWallet = new File(legacyDir, "bitcoinj-wallet.wallet");
        if (!legacyWallet.exists()) return;

        Wallet old = Wallet.loadFromFile(legacyWallet);
        if (old.network() != n) return;

        Files.move(legacyWallet.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File legacyChain = new File(legacyDir, "bitcoinj-wallet.spvchain");
        if (legacyChain.exists()) {
            File targetChain = new File(dir, walletPrefix(n) + ".spvchain");
            Files.move(legacyChain.toPath(), targetChain.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized File walletFile() {
        return walletFile(network);
    }

    public synchronized File walletFile(BitcoinNetwork n) {
        return new File(walletDir(n), walletPrefix(n) + ".wallet");
    }

    public synchronized File chainFile() {
        return chainFile(network);
    }

    public synchronized File chainFile(BitcoinNetwork n) {
        return new File(walletDir(n), walletPrefix(n) + ".spvchain");
    }

    public synchronized void ensureStorage(BitcoinNetwork n) throws Exception {
        prepareWalletStorage(n);
    }
    private final AtomicBoolean starting = new AtomicBoolean();

    public WalletManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(BitcoinNetwork network, Listener listener) {
        if (starting.get()) return;
        if (kit != null) {
            this.network = network;
            Wallet existing = kit.wallet();
            if (existing != null) {
                final double progress = lastProgress;
                final boolean isSyncing = syncing;
                main.post(() -> {
                    listener.onWalletReady(existing);
                    if (isSyncing) listener.onProgress(progress);
                    else listener.onProgress(100.0);
                });
            }
            return;
        }
        starting.set(true);
        this.network = network;
        lastProgress = 0.0;
        syncing = true;

        new Thread(() -> {
            try {
                prepareWalletStorage(network);
                File dir = walletDir(network);

                org.bitcoinj.core.Context.propagate(new org.bitcoinj.core.Context());
                kit = new WalletAppKit(
                        network,
                        ScriptType.P2WPKH,
                        KeyChainGroupStructure.BIP43,
                        dir,
                        walletPrefix(network)
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
                                final double value = Math.max(0.0, Math.min(100.0, pct));
                                synchronized (WalletManager.this) {
                                    lastProgress = value;
                                    syncing = value < 100.0;
                                }
                                main.post(() -> listener.onProgress(value));
                            }

                            @Override
                            protected void doneDownload() {
                                synchronized (WalletManager.this) {
                                    lastProgress = 100.0;
                                    syncing = false;
                                }
                                main.post(() -> listener.onProgress(100.0));
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
                prepareWalletStorage(network);
                File dir = walletDir(network);
                org.bitcoinj.core.Context.propagate(new org.bitcoinj.core.Context());
                kit = new WalletAppKit(
                        network,
                        ScriptType.P2WPKH,
                        KeyChainGroupStructure.BIP43,
                        dir,
                        walletPrefix(network)
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
                                final double value = Math.max(0.0, Math.min(100.0, pct));
                                synchronized (WalletManager.this) {
                                    lastProgress = value;
                                    syncing = value < 100.0;
                                }
                                main.post(() -> listener.onProgress(value));
                            }
                            @Override
                            protected void doneDownload() {
                                synchronized (WalletManager.this) {
                                    lastProgress = 100.0;
                                    syncing = false;
                                }
                                main.post(() -> listener.onProgress(100.0));
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
        return walletDir(network);
    }

    public synchronized boolean isSyncing() {
        return syncing;
    }

    public synchronized int progressPercent() {
        return (int) Math.round(Math.max(0.0, Math.min(100.0, lastProgress)));
    }

    public synchronized void stop() {
        lastProgress = 0.0;
        syncing = false;
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
