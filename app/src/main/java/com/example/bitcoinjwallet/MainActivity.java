package com.example.bitcoinjwallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends Activity {
    private static final String PREFS = "wallet";
    private static final String[] NETWORKS = {"MAINNET", "TESTNET", "SIGNET", "REGTEST"};

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
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("network", "MAINNET");
        try { selectedNetwork = BitcoinNetwork.valueOf(saved); }
        catch (IllegalArgumentException ignored) { selectedNetwork = BitcoinNetwork.MAINNET; }
        showWalletScreen();
        manager.start(selectedNetwork, walletListener());
    }

    private WalletManager.Listener walletListener() {
        return new WalletManager.Listener() {
            @Override public void onWalletReady(Wallet wallet) {
                runOnUiThread(() -> {
                    refreshWallet(wallet);
                    if (status != null) status.setText("Wallet ready");
                });
            }
            @Override public void onProgress(double pct) {
                int percent = (int) Math.round(Math.max(0.0, Math.min(100.0, pct)));
                runOnUiThread(() -> {
                    if (progress != null) progress.setProgress(percent);
                    if (status != null) status.setText("Synchronising  " + percent + "%");
                });
            }
            @Override public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("Sync error: " + message(error));
                });
            }
        };
    }

    private void showWalletScreen() {
        root = page();

        TextView title = title("Bitcoin Wallet");
        root.addView(title);
        root.addView(text("Simple bitcoinj wallet", 14, Color.LTGRAY));

        root.addView(section("NETWORK"));
        Spinner network = spinner(NETWORKS, selectedNetwork.name());
        root.addView(network);
        network.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                BitcoinNetwork next = BitcoinNetwork.valueOf(NETWORKS[position]);
                if (next == selectedNetwork && !first) return;
                first = false;
                if (next == selectedNetwork) return;
                selectedNetwork = next;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("network", selectedNetwork.name()).apply();
                manager.stop();
                showWalletScreen();
                manager.start(selectedNetwork, walletListener());
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        root.addView(section("WALLET"));
        balance = bigText("Balance  —");
        root.addView(balance);
        address = text("Receive address\n—", 14, Color.LTGRAY);
        address.setTextIsSelectable(true);
        root.addView(address);

        status = text("Starting…", 14, Color.LTGRAY);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 12));

        root.addView(section("ACTIONS"));
        root.addView(actionButton("Receive", "Show address and QR", v -> showReceive()));
        root.addView(actionButton("Send BTC", "Send bitcoin from this wallet", v -> showSend()));
        root.addView(actionButton("Transactions", "View wallet transaction data", v -> showTransactions()));
        root.addView(actionButton("Wallet settings", "Seed, password and restore", v -> showSettings()));
        root.addView(actionButton("Wallet Tool", "Advanced bitcoinj wallet operations", v -> showToolHome()));

        root.addView(section("WALLET CONTROL"));
        root.addView(actionButton("Sync now", "Synchronise this wallet", v -> restartWallet()));
        root.addView(actionButton("Stop wallet", "Stop bitcoinj wallet service", v -> {
            manager.stop();
            status.setText("Wallet stopped");
            progress.setProgress(0);
        }));

        setContentView(paddedScroll(root));
        if (manager.wallet() != null) refreshWallet(manager.wallet());
    }

    private void restartWallet() {
        manager.stop();
        status.setText("Starting…");
        progress.setProgress(0);
        manager.start(selectedNetwork, walletListener());
    }

    private void refreshWallet(Wallet wallet) {
        if (balance == null || address == null) return;
        balance.setText("Balance  " + wallet.getBalance().toPlainString() + " BTC");
        address.setText("Receive address\n" + wallet.currentReceiveAddress());
    }

    private void showReceive() {
        Wallet w = manager.wallet();
        if (w == null) { toast("Wallet is not ready"); return; }
        String addr = w.currentReceiveAddress().toString();
        LinearLayout box = page();
        box.addView(title("Receive BTC"));
        box.addView(text("Network  " + selectedNetwork.name(), 14, Color.LTGRAY));
        ImageView image = new ImageView(this);
        image.setImageBitmap(qr(addr, 620));
        image.setAdjustViewBounds(true);
        image.setPadding(24, 24, 24, 24);
        box.addView(image, new LinearLayout.LayoutParams(-1, 620));
        TextView a = text(addr, 16, Color.WHITE);
        a.setTextIsSelectable(true);
        a.setGravity(Gravity.CENTER);
        box.addView(a);
        box.addView(actionButton("Copy address", "Copy to clipboard", v -> copy(addr)));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void showSend() {
        Wallet w = manager.wallet();
        if (w == null) { toast("Wallet is not ready"); return; }
        LinearLayout box = page();
        box.addView(title("Send BTC"));
        box.addView(text("Available  " + w.getBalance().toPlainString() + " BTC", 15, Color.LTGRAY));
        EditText to = field("Recipient address");
        EditText amount = field("Amount BTC");
        EditText password = field("Wallet password (if encrypted)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox all = check("Send entire available balance");
        box.addView(label("RECIPIENT")); box.addView(to);
        box.addView(label("AMOUNT")); box.addView(amount); box.addView(all);
        box.addView(label("SECURITY")); box.addView(password);
        box.addView(actionButton("Review and send", "Create and broadcast transaction", v -> {
            try {
                String dest = to.getText().toString().trim();
                org.bitcoinj.base.Address target = w.parseAddress(dest);
                String pass = password.getText().toString();
                if (!pass.isEmpty() && !w.checkPassword(pass)) throw new IllegalArgumentException("Incorrect wallet password");
                SendRequest req;
                if (all.isChecked()) req = SendRequest.emptyWallet(target);
                else req = SendRequest.to(target, Coin.parseCoin(amount.getText().toString().trim()));
                if (!pass.isEmpty()) req.aesKey = w.getKeyCrypter().deriveKey(pass);
                req.allowUnconfirmed();
                new AlertDialog.Builder(this).setTitle("Confirm transaction")
                        .setMessage("To\n" + dest + "\n\nAmount\n" + (all.isChecked() ? "ALL" : amount.getText().toString().trim()) + " BTC")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("SEND", (d, which) -> broadcast(w, req)).show();
            } catch (Throwable t) { toast(message(t)); }
        }));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void broadcast(Wallet w, SendRequest req) {
        CompletableFuture.supplyAsync(() -> {
            try { return w.sendCoins(req); } catch (Exception e) { throw new RuntimeException(e); }
        }).thenAccept(result -> runOnUiThread(() -> {
            toastLong("Transaction\n" + result.tx.getTxId());
            showWalletScreen();
        })).exceptionally(t -> { runOnUiThread(() -> toastLong(message(t))); return null; });
    }

    private void showTransactions() {
        Wallet w = manager.wallet();
        LinearLayout box = page();
        box.addView(title("Transactions"));
        if (w == null || w.getTransactions(false).isEmpty()) {
            box.addView(text("No transactions in this wallet.", 15, Color.LTGRAY));
        } else {
            for (org.bitcoinj.core.Transaction tx : w.getTransactions(false)) {
                box.addView(text(tx.getTxId().toString() + "\n" + tx.getValueSentFromMe(w).toPlainString() + " BTC", 14, Color.WHITE));
            }
        }
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void showSettings() {
        Wallet w = manager.wallet();
        if (w == null) { toast("Wallet is not ready"); return; }
        LinearLayout box = page();
        box.addView(title("Wallet settings"));
        box.addView(text("Network  " + selectedNetwork.name(), 14, Color.LTGRAY));
        box.addView(actionButton("Recovery seed", "Show mnemonic words", v -> showSeed(w)));
        box.addView(actionButton(w.isEncrypted() ? "Decrypt wallet" : "Set password", "Change wallet encryption", v -> toggleEncryption(w)));
        box.addView(actionButton("Restore wallet", "Restore from a 12-word mnemonic", v -> showRestore()));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void showSeed(Wallet w) {
        try {
            DeterministicSeed seed = w.getKeyChainSeed();
            if (seed.isEncrypted()) {
                askPassword("Wallet password", pass -> {
                    try {
                        if (!w.checkPassword(pass)) throw new IllegalArgumentException("Wrong password");
                        AesKey key = w.getKeyCrypter().deriveKey(pass);
                        showSeedText(seed.decrypt(w.getKeyCrypter(), "", key));
                    } catch (Throwable t) { toast(message(t)); }
                });
            } else showSeedText(seed);
        } catch (Throwable t) { toast(message(t)); }
    }

    private void showSeedText(DeterministicSeed seed) {
        String words = seed.getMnemonicCode() == null ? seed.toString() : String.join(" ", seed.getMnemonicCode());
        new AlertDialog.Builder(this).setTitle("Recovery seed")
                .setMessage(words)
                .setPositiveButton("Close", null)
                .setNegativeButton("Copy", (d, w) -> copy(words)).show();
    }

    private void toggleEncryption(Wallet w) {
        if (w.isEncrypted()) {
            askPassword("Decrypt wallet", pass -> {
                try {
                    if (!w.checkPassword(pass)) throw new IllegalArgumentException("Wrong password");
                    w.decrypt(w.getKeyCrypter().deriveKey(pass));
                    toast("Wallet decrypted"); showSettings();
                } catch (Throwable t) { toast(message(t)); }
            });
        } else {
            askTwoPasswords("Set wallet password", (a, b) -> {
                if (!a.equals(b) || a.length() < 4) { toast("Passwords must match and be at least 4 characters"); return; }
                try {
                    KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
                    w.encrypt(scrypt, scrypt.deriveKey(a));
                    toast("Wallet encrypted"); showSettings();
                } catch (Throwable t) { toast(message(t)); }
            });
        }
    }

    private void showRestore() {
        LinearLayout box = page();
        box.addView(title("Restore wallet"));
        EditText words = field("12-word mnemonic");
        EditText date = field("Birthday YYYY-MM-DD (UTC)");
        box.addView(label("RECOVERY WORDS")); box.addView(words);
        box.addView(label("BIRTHDAY")); box.addView(date);
        box.addView(actionButton("Restore and resync", "Replace the current wallet", v -> {
            try {
                if (wBalance() > 0) throw new IllegalStateException("Wallet must be empty before restore");
                List<String> mnemonic = Arrays.asList(words.getText().toString().trim().split("\\s+"));
                org.bitcoinj.crypto.MnemonicCode.INSTANCE.check(mnemonic);
                LocalDate d = LocalDate.parse(date.getText().toString().trim());
                DeterministicSeed seed = DeterministicSeed.ofMnemonic(mnemonic, "", d.atStartOfDay().toInstant(ZoneOffset.UTC));
                manager.restartFromSeed(seed, walletListener());
                showWalletScreen();
            } catch (Throwable t) { toast(message(t)); }
        }));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private long wBalance() { return manager.wallet() == null ? 0 : manager.wallet().getBalance().value; }

    private void showToolHome() {
        LinearLayout box = page();
        box.addView(title("Wallet Tool"));
        box.addView(text("Advanced bitcoinj operations for this wallet.", 14, Color.LTGRAY));
        box.addView(text("Network  " + selectedNetwork.name(), 14, Color.LTGRAY));
        box.addView(section("COMMON"));
        toolAction(box, "Inspect wallet", "Show wallet details", "dump");
        toolAction(box, "Sync wallet", "Download new transactions", "sync");
        toolAction(box, "Receive address", "Get the current receive address", "current-receive-addr");
        toolAction(box, "Send BTC", "Create and broadcast a transaction", "send");
        box.addView(section("WALLET"));
        toolAction(box, "Create wallet", "Create a new wallet file", "create");
        toolAction(box, "Encrypt wallet", "Encrypt this wallet", "encrypt");
        toolAction(box, "Decrypt wallet", "Decrypt this wallet", "decrypt");
        toolAction(box, "Add address", "Add a watching address", "add-addr");
        toolAction(box, "Add key", "Add a private/public key", "add-key");
        toolAction(box, "Delete key", "Remove a key or address", "delete-key");
        toolAction(box, "Reset wallet", "Delete transactions and replay chain", "reset");
        box.addView(section("ADVANCED"));
        toolAction(box, "Raw dump", "Print raw wallet protobuf", "raw-dump");
        toolAction(box, "Upgrade wallet", "Upgrade deterministic wallet", "upgrade");
        toolAction(box, "Rotate keys", "Set key rotation time", "rotate");
        toolAction(box, "Set creation time", "Repair wallet creation time", "set-creation-time");
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void toolAction(LinearLayout box, String name, String desc, String action) {
        box.addView(actionButton(name, desc, v -> showToolAction(action)));
    }

    private void showToolAction(String action) {
        LinearLayout box = page();
        box.addView(title(toolTitle(action)));
        box.addView(text("Network  " + selectedNetwork.name(), 14, Color.LTGRAY));
        List<EditText> fields = new ArrayList<>();
        CheckBox force = null, allow = null, dumpPriv = null, dumpLook = null, offline = null;

        switch (action) {
            case "dump":
                fields.add(addField(box, "Password (if encrypted)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                dumpPriv = check("Show private keys and seed"); box.addView(dumpPriv);
                dumpLook = check("Show lookahead keys"); box.addView(dumpLook);
                break;
            case "create":
                fields.add(addField(box, "Mnemonic seed (optional)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Watch xpub (optional)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Birthday YYYY-MM-DD", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Script type: P2PKH or P2WPKH", InputType.TYPE_CLASS_TEXT));
                force = check("Force if wallet already exists"); box.addView(force);
                break;
            case "add-key":
                fields.add(addField(box, "Private key (WIF/hex/base58)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Public key (optional)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Creation date YYYY-MM-DD (optional)", InputType.TYPE_CLASS_TEXT));
                break;
            case "add-addr":
                fields.add(addField(box, "Bitcoin address", InputType.TYPE_CLASS_TEXT));
                break;
            case "delete-key":
                fields.add(addField(box, "Public key or address", InputType.TYPE_CLASS_TEXT));
                break;
            case "encrypt":
            case "decrypt":
                fields.add(addField(box, "Wallet password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                break;
            case "send":
                fields.add(addField(box, "Recipient address", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Amount BTC (or ALL)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Fee sat/vByte (optional)", InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, "Wallet password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                allow = check("Allow unconfirmed outputs"); box.addView(allow);
                offline = check("Offline: create transaction only"); box.addView(offline);
                break;
            case "sync":
                force = check("Force reset chain before sync"); box.addView(force);
                break;
            case "rotate":
                fields.add(addField(box, "Rotation date YYYY-MM-DD", InputType.TYPE_CLASS_TEXT));
                break;
            case "set-creation-time":
                fields.add(addField(box, "Creation date YYYY-MM-DD (leave empty to clear)", InputType.TYPE_CLASS_TEXT));
                break;
            case "upgrade":
                fields.add(addField(box, "Script type: P2PKH or P2WPKH", InputType.TYPE_CLASS_TEXT));
                break;
            case "reset":
                force = check("Force reset"); box.addView(force);
                break;
            default:
                break;
        }

        TextView result = text("", 13, Color.LTGRAY); result.setTextIsSelectable(true);
        Button run = actionButton("Run", "Execute this wallet-tool action", v -> {
            List<String> args = new ArrayList<>(); args.add(action); args.add("--net=" + selectedNetwork.name());
            buildToolArgs(action, fields, force, allow, offline, dumpPriv, dumpLook, args);
            run.setEnabled(false); result.setText("Running…");
            boolean restart = manager.wallet() != null && manager.network() == selectedNetwork;
            if (restart) manager.stop();
            toolRunner.run(args, selectedNetwork, new ToolRunner.Callback() {
                @Override public void onStarted() { runOnUiThread(() -> result.setText("Running…\n" + manager.walletFile(selectedNetwork).getName())); }
                @Override public void onFinished(int code, String output) { runOnUiThread(() -> { run.setEnabled(true); result.setText("Exit code: " + code + "\n\n" + (output.isEmpty() ? "(no output)" : output)); if (restart) manager.start(selectedNetwork, walletListener()); }); }
                @Override public void onFailed(Throwable error) { runOnUiThread(() -> { run.setEnabled(true); result.setText("Failed\n" + message(error)); if (restart) manager.start(selectedNetwork, walletListener()); }); }
            });
        });
        box.addView(run); box.addView(result); box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void buildToolArgs(String action, List<EditText> f, CheckBox force, CheckBox allow, CheckBox offline, CheckBox dumpPriv, CheckBox dumpLook, List<String> args) {
        int i = 0;
        if (action.equals("dump")) { add(args, "--password", f, i++); if (dumpPriv != null && dumpPriv.isChecked()) args.add("--dump-privkeys"); if (dumpLook != null && dumpLook.isChecked()) args.add("--dump-lookahead"); }
        else if (action.equals("create")) { add(args, "--seed", f, i++); add(args, "--watchkey", f, i++); add(args, "--date", f, i++); add(args, "--output-script-type", f, i++); if (force != null && force.isChecked()) args.add("--force"); }
        else if (action.equals("add-key")) { add(args, "--privkey", f, i++); add(args, "--pubkey", f, i++); add(args, "--date", f, i); }
        else if (action.equals("add-addr")) add(args, "--addr", f, i);
        else if (action.equals("delete-key")) add(args, "--addr", f, i);
        else if (action.equals("encrypt") || action.equals("decrypt")) add(args, "--password", f, i);
        else if (action.equals("send")) { String to = value(f, i++), amount = value(f, i++), fee = value(f, i++), pass = value(f, i); if (!to.isEmpty() && !amount.isEmpty()) args.add("--output=" + to + ":" + amount); if (!fee.isEmpty()) args.add("--fee-sat-per-vbyte=" + fee); if (!pass.isEmpty()) args.add("--password=" + pass); if (allow != null && allow.isChecked()) args.add("--allow-unconfirmed"); if (offline != null && offline.isChecked()) args.add("--offline"); }
        else if (action.equals("sync")) { if (force != null && force.isChecked()) args.add("--force"); }
        else if (action.equals("rotate") || action.equals("set-creation-time")) add(args, "--date", f, i);
        else if (action.equals("upgrade")) add(args, "--output-script-type", f, i);
        else if (action.equals("reset")) { if (force != null && force.isChecked()) args.add("--force"); }
    }

    private EditText addField(LinearLayout box, String hint, int type) { EditText e = field(hint); e.setInputType(type); box.addView(e); return e; }
    private String value(List<EditText> f, int i) { return i < f.size() ? f.get(i).getText().toString().trim() : ""; }
    private void add(List<String> args, String key, List<EditText> f, int i) { String v = value(f, i); if (!v.isEmpty()) args.add(key + "=" + v); }

    private String toolTitle(String a) {
        switch (a) { case "dump": return "Inspect wallet"; case "sync": return "Sync wallet"; case "current-receive-addr": return "Receive address"; case "send": return "Send BTC"; case "add-addr": return "Add address"; case "add-key": return "Add key"; case "delete-key": return "Delete key"; case "raw-dump": return "Raw dump"; case "set-creation-time": return "Set creation time"; default: return Character.toUpperCase(a.charAt(0)) + a.substring(1); }
    }

    private void askPassword(String title, PasswordCallback cb) { EditText input = field("Password"); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("OK", (d,w) -> cb.accept(input.getText().toString())).setNegativeButton("Cancel", null).show(); }
    private void askTwoPasswords(String title, TwoPasswordCallback cb) { LinearLayout box = page(); EditText a = field("Password"); EditText b = field("Repeat password"); a.setInputType(129); b.setInputType(129); box.addView(a); box.addView(b); new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("OK", (d,w) -> cb.accept(a.getText().toString(), b.getText().toString())).setNegativeButton("Cancel", null).show(); }
    private interface PasswordCallback { void accept(String password); }
    private interface TwoPasswordCallback { void accept(String a, String b); }

    private LinearLayout page() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(24, 20, 24, 28); l.setBackgroundColor(Color.rgb(16,16,16)); return l; }
    private ScrollView paddedScroll(View child) { ScrollView s = new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(Color.rgb(16,16,16)); s.addView(child); return s; }
    private TextView title(String s) { return text(s, 26, Color.WHITE); }
    private TextView bigText(String s) { TextView t = text(s, 23, Color.WHITE); t.setPadding(0, 8, 0, 16); return t; }
    private TextView section(String s) { TextView t = text(s, 12, Color.rgb(247,147,26)); t.setPadding(0, 22, 0, 7); return t; }
    private TextView label(String s) { TextView t = text(s, 11, Color.rgb(170,170,170)); t.setPadding(0, 12, 0, 2); return t; }
    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, 7, 0, 7); return t; }
    private Button actionButton(String name, String desc, View.OnClickListener listener) { Button b = new Button(this); b.setText(name + "\n" + desc); b.setTextSize(15); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); b.setAllCaps(false); b.setPadding(18, 8, 18, 8); b.setOnClickListener(listener); b.setMinHeight(64); return b; }
    private Button backButton() { return actionButton("Back", "Return to wallet", v -> showWalletScreen()); }
    private Button button(String s) { return actionButton(s, "", null); }
    private Spinner spinner(String[] values, String selected) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); s.setSelection(Arrays.asList(values).indexOf(selected)); return s; }
    private EditText field(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(Color.WHITE); e.setTextSize(16); e.setSingleLine(true); e.setPadding(0, 10, 0, 10); return e; }
    private CheckBox check(String label) { CheckBox c = new CheckBox(this); c.setText(label); c.setTextColor(Color.WHITE); c.setAllCaps(false); return c; }
    private void copy(String value) { ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("bitcoin", value)); toast("Copied"); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void toastLong(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private Bitmap qr(String value, int size) { try { BitMatrix m = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size); Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565); for (int x=0;x<size;x++) for(int y=0;y<size;y++) b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE); return b; } catch(Exception e){ throw new IllegalStateException(e); } }
    private String message(Throwable t) { Throwable x=t; while(x.getCause()!=null&&x.getCause()!=x)x=x.getCause(); return x.getMessage()==null?x.toString():x.getMessage(); }

    @Override protected void onDestroy() { toolRunner.shutdown(); manager.stop(); super.onDestroy(); }
}
