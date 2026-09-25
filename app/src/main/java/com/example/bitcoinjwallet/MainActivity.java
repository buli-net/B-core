package com.example.bitcoinjwallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends Activity {
    private WalletManager manager;
    private ToolRunner toolRunner;
    private LinearLayout root;
    private TextView status;
    private TextView balance;
    private TextView address;
    private ProgressBar progress;
    private BitcoinNetwork selectedNetwork = BitcoinNetwork.MAINNET;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = new WalletManager(this);
        toolRunner = new ToolRunner(this, manager);
        String saved = getSharedPreferences("wallet", MODE_PRIVATE)
                .getString("network", BitcoinNetwork.MAINNET.name());
        try { selectedNetwork = BitcoinNetwork.valueOf(saved); }
        catch (IllegalArgumentException ignored) { selectedNetwork = BitcoinNetwork.MAINNET; }
        showNetworkChooser();
    }

    private void showNetworkChooser() {
        LinearLayout box = column();
        TextView title = text("BitcoinJ Android Wallet", 24);
        box.addView(title);
        box.addView(text("Select the Bitcoin network used by this wallet.", 16));

        Spinner spinner = new Spinner(this);
        String[] networks = {"TESTNET", "MAINNET", "SIGNET", "REGTEST"};
        spinner.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, networks));
        spinner.setSelection(Arrays.asList(networks).indexOf(selectedNetwork.name()));
        box.addView(spinner);

        Button start = button("Open wallet");
        box.addView(start);
        start.setOnClickListener(v -> {
            String n = spinner.getSelectedItem().toString();
            selectedNetwork = BitcoinNetwork.valueOf(n);
            getSharedPreferences("wallet", MODE_PRIVATE).edit()
                    .putString("network", selectedNetwork.name()).apply();
            showWalletScreen();
            manager.start(selectedNetwork, walletListener());
        });

        setContentView(paddedScroll(box));
    }

    private WalletManager.Listener walletListener() {
        return new WalletManager.Listener() {
            @Override public void onWalletReady(Wallet wallet) {
                refreshWallet(wallet);
                status.setText("Wallet ready. Synchronisation continues in background.");
            }
            @Override public void onProgress(double pct) {
                int percent = (int) Math.round(Math.max(0.0, Math.min(100.0, pct)));
                progress.setProgress(percent);
                status.setText("Synchronising: " + percent + "%");
            }
            @Override public void onError(Throwable error) {
                status.setText("Error: " + message(error));
            }
        };
    }

    private void showWalletScreen() {
        root = column();
        TextView title = text("BitcoinJ Wallet", 24);
        root.addView(title);

        status = text("Starting bitcoinj...", 14);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress);

        balance = text("Balance: -- BTC", 22);
        root.addView(balance);

        address = text("Receive address: --", 14);
        address.setTextIsSelectable(true);
        root.addView(address);

        LinearLayout actions = column();
        actions.addView(button("Receive / QR", v -> showReceive()));
        actions.addView(button("Send BTC", v -> showSend()));
        actions.addView(button("Wallet settings", v -> showSettings()));
        actions.addView(button("Wallet Tool", v -> showTool()));
        actions.addView(button("Refresh", v -> {
            if (manager.wallet() != null) refreshWallet(manager.wallet());
        }));
        actions.addView(button("Stop wallet", v -> {
            manager.stop();
            status.setText("Wallet stopped.");
        }));
        root.addView(actions);

        setContentView(paddedScroll(root));
    }

    private void refreshWallet(Wallet wallet) {
        if (balance == null || address == null) return;
        Coin c = wallet.getBalance();
        balance.setText("Balance: " + c.toPlainString() + " BTC");
        address.setText("Receive address:\n" + wallet.currentReceiveAddress());
    }

    private void showReceive() {
        Wallet w = manager.wallet();
        if (w == null) return;
        String addr = w.currentReceiveAddress().toString();
        Bitmap qr = qr(addr, 700);
        LinearLayout box = column();
        box.addView(text("Receive", 24));
        TextView a = text(addr, 16);
        a.setTextIsSelectable(true);
        box.addView(a);
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageBitmap(qr);
        image.setAdjustViewBounds(true);
        box.addView(image);
        box.addView(button("Back", v -> showWalletScreenAndRefresh()));
        setContentView(paddedScroll(box));
    }

    private void showWalletScreenAndRefresh() {
        showWalletScreen();
        if (manager.wallet() != null) refreshWallet(manager.wallet());
    }

    private void showSend() {
        Wallet w = manager.wallet();
        if (w == null) return;

        LinearLayout box = column();
        box.addView(text("Send BTC", 24));

        EditText to = field("Destination address");
        EditText amount = field("Amount BTC");
        EditText password = field("Wallet password (only if encrypted)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox empty = new CheckBox(this);
        empty.setText("Send entire available balance");

        box.addView(to);
        box.addView(amount);
        box.addView(password);
        box.addView(empty);

        Button send = button("Create and broadcast");
        box.addView(send);
        box.addView(button("Back", v -> showWalletScreenAndRefresh()));

        send.setOnClickListener(v -> {
            try {
                String dest = to.getText().toString().trim();
                Coin value = Coin.parseCoin(amount.getText().toString().trim());
                org.bitcoinj.base.Address addr = w.parseAddress(dest);
                org.bitcoinj.wallet.SendRequest req = empty.isChecked()
                        ? org.bitcoinj.wallet.SendRequest.emptyWallet(addr)
                        : org.bitcoinj.wallet.SendRequest.to(addr, value);
                if (!password.getText().toString().isEmpty()) {
                    if (!w.checkPassword(password.getText().toString())) {
                        throw new IllegalArgumentException("Incorrect wallet password");
                    }
                    req.aesKey = w.getKeyCrypter().deriveKey(password.getText().toString());
                }
                req.allowUnconfirmed();
                send.setEnabled(false);
                CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return w.sendCoins(req);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .thenAccept(result -> runOnUiThread(() -> {
                            Toast.makeText(this, "Transaction: " + result.tx.getTxId(),
                                    Toast.LENGTH_LONG).show();
                            showWalletScreenAndRefresh();
                        }))
                        .exceptionally(t -> {
                            runOnUiThread(() -> {
                                send.setEnabled(true);
                                Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
                            });
                            return null;
                        });
            } catch (Throwable t) {
                Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
            }
        });

        setContentView(paddedScroll(box));
    }

    private void showSettings() {
        Wallet w = manager.wallet();
        if (w == null) return;

        LinearLayout box = column();
        box.addView(text("Wallet settings", 24));

        TextView info = text("", 14);
        box.addView(info);

        Button seed = button("Show mnemonic seed");
        Button encrypt = button(w.isEncrypted() ? "Decrypt wallet" : "Set wallet password");
        Button restore = button("Restore from mnemonic");
        Button back = button("Back", v -> showWalletScreenAndRefresh());
        box.addView(seed);
        box.addView(encrypt);
        box.addView(restore);
        box.addView(back);

        seed.setOnClickListener(v -> showSeed(w, info));
        encrypt.setOnClickListener(v -> {
            if (w.isEncrypted()) {
                askPassword("Decrypt wallet", pass -> {
                    try {
                        if (!w.checkPassword(pass)) throw new IllegalArgumentException("Wrong password");
                        AesKey key = w.getKeyCrypter().deriveKey(pass);
                        w.decrypt(key);
                        encrypt.setText("Set wallet password");
                        Toast.makeText(this, "Wallet decrypted", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                askTwoPasswords("Set wallet password", (p1, p2) -> {
                    if (!p1.equals(p2) || p1.length() < 4) {
                        Toast.makeText(this, "Passwords must match and be at least 4 characters", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
                        AesKey key = scrypt.deriveKey(p1);
                        w.encrypt(scrypt, key);
                        encrypt.setText("Decrypt wallet");
                        Toast.makeText(this, "Wallet encrypted", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        restore.setOnClickListener(v -> showRestore());
        setContentView(paddedScroll(box));
    }

    private void showSeed(Wallet w, TextView info) {
        try {
            DeterministicSeed seed = w.getKeyChainSeed();
            if (seed.isEncrypted()) {
                askPassword("Wallet password", pass -> {
                    try {
                        if (!w.checkPassword(pass)) throw new IllegalArgumentException("Wrong password");
                        AesKey key = w.getKeyCrypter().deriveKey(pass);
                        DeterministicSeed clear = seed.decrypt(w.getKeyCrypter(), "", key);
                        showSeedText(clear);
                    } catch (Throwable t) {
                        Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                showSeedText(seed);
            }
        } catch (Throwable t) {
            Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
        }
    }

    private void showSeedText(DeterministicSeed seed) {
        new AlertDialog.Builder(this)
                .setTitle("Recovery seed")
                .setMessage(seed.getMnemonicCode() == null
                        ? seed.toString()
                        : String.join(" ", seed.getMnemonicCode()))
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "seed", String.join(" ", seed.getMnemonicCode())));
                })
                .show();
    }

    private void showRestore() {
        LinearLayout box = column();
        box.addView(text("Restore wallet", 24));
        EditText words = field("12-word mnemonic");
        EditText date = field("Birthday YYYY-MM-DD (UTC)");
        box.addView(words);
        box.addView(date);
        box.addView(button("Restore and resync", v -> {
            try {
                List<String> mnemonic = Arrays.asList(words.getText().toString().trim().split("\\s+"));
                org.bitcoinj.crypto.MnemonicCode.INSTANCE.check(mnemonic);
                LocalDate d = LocalDate.parse(date.getText().toString().trim());
                Instant birthday = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                DeterministicSeed seed = DeterministicSeed.ofMnemonic(mnemonic, "", birthday);
                if (wBalance() > 0) throw new IllegalStateException("Wallet must be empty before restore");
                manager.restartFromSeed(seed, walletListener());
                showWalletScreen();
            } catch (Throwable t) {
                Toast.makeText(this, message(t), Toast.LENGTH_LONG).show();
            }
        }));
        box.addView(button("Back", v -> showWalletScreenAndRefresh()));
        setContentView(paddedScroll(box));
    }

    private long wBalance() {
        return manager.wallet() == null ? 0 : manager.wallet().getBalance().value;
    }

    private void showTool() {
        LinearLayout box = column();
        box.addView(text("BitcoinJ Wallet Tool", 24));
        box.addView(text("Runs wallet-tool against the wallet for the selected network.", 14));

        Spinner action = new Spinner(this);
        String[] actions = {
                "dump","raw-dump","create","add-key","add-addr","delete-key",
                "current-receive-addr","sync","reset","send","encrypt","decrypt",
                "upgrade","rotate","set-creation-time"
        };
        action.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, actions));
        box.addView(action);

        Spinner net = new Spinner(this);
        net.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"TESTNET","MAINNET","SIGNET","REGTEST"}));
        net.setSelection(Arrays.asList("TESTNET","MAINNET","SIGNET","REGTEST").indexOf(selectedNetwork.name()));
        box.addView(net);

        EditText seed = field("seed / mnemonic");
        EditText watchkey = field("watch xpub");
        EditText outputScript = field("output script type: P2PKH or P2WPKH");
        outputScript.setText("P2PKH");
        EditText date = field("date YYYY-MM-DD");
        EditText unix = field("unix time");
        EditText pubkey = field("public key");
        EditText privkey = field("private key");
        EditText addr = field("address");
        EditText peers = field("peers: host,host");
        EditText xpubkeys = field("external xpub keys");
        EditText selectAddr = field("select address");
        EditText selectOutput = field("select txhash:index");
        EditText outputs = field("output address:value; multiple separated by newline");
        EditText feeVkb = field("fee per vKB (BTC)");
        EditText feeVbyte = field("fee sat/vByte");
        EditText condition = field("condition, e.g. >5.10");
        EditText locktime = field("locktime");
        EditText password = field("password");
        EditText waitfor = field("waitfor: EVER/WALLET_TX/BLOCK/BALANCE");
        EditText chain = field("optional chain file path");
        CheckBox force = check("force");
        CheckBox allowUnconfirmed = check("allow unconfirmed");
        CheckBox offline = check("offline");
        CheckBox noPki = check("no PKI");
        CheckBox dumpPriv = check("dump private keys");
        CheckBox dumpLook = check("dump lookahead");
        CheckBox ignoreExt = check("ignore mandatory extensions");
        CheckBox debug = check("debug log");

        for (View v : new View[]{seed,watchkey,outputScript,date,unix,pubkey,privkey,addr,peers,xpubkeys,
                selectAddr,selectOutput,outputs,feeVkb,feeVbyte,condition,locktime,password,waitfor,chain,
                force,allowUnconfirmed,offline,noPki,dumpPriv,dumpLook,ignoreExt,debug}) box.addView(v);

        Button run = button("Run tool");
        box.addView(run);
        TextView result = text("", 12);
        result.setTextIsSelectable(true);
        box.addView(result);
        box.addView(button("Back", v -> showWalletScreenAndRefresh()));

        run.setOnClickListener(v -> {
            List<String> a = new ArrayList<>();
            a.add(action.getSelectedItem().toString());
            a.add("--net=" + net.getSelectedItem().toString());
            add(a, "--seed", seed);
            add(a, "--watchkey", watchkey);
            add(a, "--output-script-type", outputScript);
            add(a, "--date", date);
            add(a, "--unixtime", unix);
            add(a, "--pubkey", pubkey);
            add(a, "--privkey", privkey);
            add(a, "--addr", addr);
            add(a, "--peers", peers);
            add(a, "--xpubkeys", xpubkeys);
            add(a, "--select-addr", selectAddr);
            add(a, "--select-output", selectOutput);
            for (String line : outputs.getText().toString().split("\\R")) {
                if (!line.trim().isEmpty()) a.add("--output=" + line.trim());
            }
            add(a, "--fee-per-vkb", feeVkb);
            add(a, "--fee-sat-per-vbyte", feeVbyte);
            add(a, "--condition", condition);
            add(a, "--locktime", locktime);
            add(a, "--password", password);
            add(a, "--waitfor", waitfor);
            if (!chain.getText().toString().trim().isEmpty()) add(a, "--chain", chain);
            if (force.isChecked()) a.add("--force");
            if (allowUnconfirmed.isChecked()) a.add("--allow-unconfirmed");
            if (offline.isChecked()) a.add("--offline");
            if (noPki.isChecked()) a.add("--no-pki");
            if (dumpPriv.isChecked()) a.add("--dump-privkeys");
            if (dumpLook.isChecked()) a.add("--dump-lookahead");
            if (ignoreExt.isChecked()) a.add("--ignore-mandatory-extensions");
            if (debug.isChecked()) a.add("--debuglog");

            BitcoinNetwork toolNetwork = BitcoinNetwork.valueOf(net.getSelectedItem().toString());
            boolean restartWallet = manager.wallet() != null && manager.network() == toolNetwork;
            run.setEnabled(false);
            result.setText("Starting...");
            new Thread(() -> {
                if (restartWallet) manager.stop();
                toolRunner.run(a, toolNetwork, new ToolRunner.Callback() {
                    @Override public void onStarted() {
                        runOnUiThread(() -> result.setText("Running...\nWallet: " + manager.walletFile(toolNetwork).getAbsolutePath()));
                    }
                    @Override public void onFinished(int exitCode, String output) {
                        runOnUiThread(() -> {
                            run.setEnabled(true);
                            result.setText("Exit code: " + exitCode + "\n\n" + output);
                            if (restartWallet) {
                                status = status == null ? text("", 14) : status;
                                manager.start(toolNetwork, walletListener());
                            }
                        });
                    }
                    @Override public void onFailed(Throwable error) {
                        runOnUiThread(() -> {
                            run.setEnabled(true);
                            result.setText("Failed:\n" + message(error));
                            if (restartWallet) manager.start(toolNetwork, walletListener());
                        });
                    }
                });
            }, "wallet-tool").start();
        });

        setContentView(paddedScroll(box));
    }

    private static void add(List<String> a, String key, EditText value) {
        String s = value.getText().toString().trim();
        if (!s.isEmpty()) a.add(key + "=" + s);
    }

    private void askPassword(String title, PasswordCallback cb) {
        EditText input = field("Password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("OK", (d,w) -> cb.accept(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void askTwoPasswords(String title, TwoPasswordCallback cb) {
        LinearLayout box = column();
        EditText p1 = field("Password");
        EditText p2 = field("Repeat password");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1); box.addView(p2);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("OK", (d,w) -> cb.accept(p1.getText().toString(), p2.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private interface PasswordCallback { void accept(String password); }
    private interface TwoPasswordCallback { void accept(String a, String b); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(24, 24, 24, 24);
        return l;
    }

    private ScrollView paddedScroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }

    private TextView text(String value, float size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private Button button(String label) { return button(label, null); }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        if (listener != null) b.setOnClickListener(listener);
        return b;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.LTGRAY);
        e.setSingleLine(false);
        e.setPadding(0, 10, 0, 10);
        return e;
    }

    private CheckBox check(String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.WHITE);
        return c;
    }

    private Bitmap qr(String value, int size) {
        try {
            BitMatrix m = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return b;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String message(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.toString() : x.getMessage();
    }

    @Override
    protected void onDestroy() {
        toolRunner.shutdown();
        manager.stop();
        super.onDestroy();
    }
}
