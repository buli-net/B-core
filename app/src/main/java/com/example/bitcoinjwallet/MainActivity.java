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
                    if (status != null) status.setText(s(R.string.wallet_ready));
                });
            }
            @Override public void onProgress(double pct) {
                int percent = (int) Math.round(Math.max(0.0, Math.min(100.0, pct)));
                runOnUiThread(() -> {
                    if (progress != null) progress.setProgress(percent);
                    if (status != null) status.setText(getString(R.string.synchronising_percent, percent));
                });
            }
            @Override public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (status != null) status.setText(getString(R.string.sync_error, message(error)));
                });
            }
        };
    }

    private void showWalletScreen() {
        root = page();

        TextView title = title(s(R.string.app_name));
        root.addView(title);
        root.addView(text(s(R.string.simple_wallet), 14, textSecondaryColor()));

        root.addView(section(s(R.string.network)));
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

        root.addView(section(s(R.string.wallet)));
        balance = bigText(s(R.string.balance_unknown));
        root.addView(balance);
        address = text(s(R.string.receive_address_unknown), 14, textSecondaryColor());
        address.setTextIsSelectable(true);
        root.addView(address);

        status = text(s(R.string.starting), 14, textSecondaryColor());
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 12));

        root.addView(section(s(R.string.actions)));
        root.addView(actionButton(s(R.string.receive), s(R.string.show_address_qr), v -> showReceive()));
        root.addView(actionButton(s(R.string.send_btc), s(R.string.send_from_wallet), v -> showSend()));
        root.addView(actionButton(s(R.string.transactions), s(R.string.view_transactions), v -> showTransactions()));
        root.addView(actionButton(s(R.string.wallet_settings), s(R.string.seed_password_restore), v -> showSettings()));
        root.addView(actionButton(s(R.string.wallet_tool), s(R.string.advanced_operations), v -> showToolHome()));

        root.addView(section(s(R.string.wallet_control)));
        root.addView(actionButton(s(R.string.sync_now), s(R.string.synchronise_wallet), v -> restartWallet()));
        root.addView(actionButton(s(R.string.stop_wallet), s(R.string.stop_wallet_desc), v -> {
            manager.stop();
            status.setText(s(R.string.wallet_stopped));
            progress.setProgress(0);
        }));

        setContentView(paddedScroll(root));
        Wallet current = manager.wallet();
        if (current != null) {
            refreshWallet(current);
            if (manager.isSyncing()) {
                status.setText(getString(R.string.synchronising_percent, manager.progressPercent()));
                progress.setProgress(manager.progressPercent());
            } else {
                status.setText(s(R.string.wallet_ready));
                progress.setProgress(100);
            }
        }
    }

    private void restartWallet() {
        manager.stop();
        status.setText(s(R.string.starting));
        progress.setProgress(0);
        manager.start(selectedNetwork, walletListener());
    }

    private void refreshWallet(Wallet wallet) {
        if (balance == null || address == null) return;
        balance.setText(getString(R.string.balance_value, wallet.getBalance().toPlainString()));
        address.setText(getString(R.string.receive_address_value, wallet.currentReceiveAddress()));
    }

    private void showReceive() {
        Wallet w = manager.wallet();
        if (w == null) { toast(s(R.string.wallet_not_ready)); return; }
        try {
            String addr = w.currentReceiveAddress().toString();
            LinearLayout box = page();
            box.addView(title(s(R.string.receive_btc)));
            box.addView(text(getString(R.string.network_value, selectedNetwork.name()), 14, textSecondaryColor()));

            try {
                ImageView image = new ImageView(this);
                image.setImageBitmap(qr(addr, 620));
                image.setAdjustViewBounds(true);
                image.setPadding(24, 24, 24, 24);
                box.addView(image, new LinearLayout.LayoutParams(-1, 620));
            } catch (Throwable qrError) {
                box.addView(text(s(R.string.qr_unavailable), 14, textSecondaryColor()));
            }

            TextView a = text(addr, 16, textPrimaryColor());
            a.setTextIsSelectable(true);
            a.setGravity(Gravity.CENTER);
            box.addView(a);
            box.addView(actionButton(s(R.string.copy_address), s(R.string.copy_to_clipboard), v -> copy(addr)));
            box.addView(backButton());
            setContentView(paddedScroll(box));
        } catch (Throwable error) {
            toastLong(message(error));
        }
    }

    private void showSend() {
        Wallet w = manager.wallet();
        if (w == null) { toast(s(R.string.wallet_not_ready)); return; }
        LinearLayout box = page();
        box.addView(title(s(R.string.send_btc)));
        box.addView(text(getString(R.string.available_btc, w.getBalance().toPlainString()), 15, textSecondaryColor()));
        EditText to = field(s(R.string.recipient_address));
        EditText amount = field(s(R.string.amount_btc));
        EditText password = field(s(R.string.wallet_password_if_encrypted));
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox all = check(s(R.string.send_entire_balance));
        box.addView(label(s(R.string.recipient))); box.addView(to);
        box.addView(label(s(R.string.amount))); box.addView(amount); box.addView(all);
        box.addView(label(s(R.string.security))); box.addView(password);
        box.addView(actionButton(s(R.string.review_and_send), s(R.string.create_broadcast_transaction), v -> {
            try {
                String dest = to.getText().toString().trim();
                org.bitcoinj.base.Address target = w.parseAddress(dest);
                String pass = password.getText().toString();
                if (!pass.isEmpty() && !w.checkPassword(pass)) throw new IllegalArgumentException(s(R.string.incorrect_wallet_password));
                SendRequest req;
                if (all.isChecked()) req = SendRequest.emptyWallet(target);
                else req = SendRequest.to(target, Coin.parseCoin(amount.getText().toString().trim()));
                if (!pass.isEmpty()) req.aesKey = w.getKeyCrypter().deriveKey(pass);
                req.allowUnconfirmed();
                new AlertDialog.Builder(this).setTitle(s(R.string.confirm_transaction))
                        .setMessage(getString(R.string.confirm_send_message, dest, all.isChecked() ? s(R.string.all) : amount.getText().toString().trim()))
                        .setNegativeButton(s(R.string.cancel), null)
                        .setPositiveButton(s(R.string.send_upper), (d, which) -> broadcast(w, req)).show();
            } catch (Throwable t) { toast(message(t)); }
        }));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void broadcast(Wallet w, SendRequest req) {
        CompletableFuture.supplyAsync(() -> {
            try { return w.sendCoins(req); } catch (Exception e) { throw new RuntimeException(e); }
        }).thenAccept(result -> runOnUiThread(() -> {
            toastLong(getString(R.string.transaction_id, result.tx.getTxId()));
            showWalletScreen();
        })).exceptionally(t -> { runOnUiThread(() -> toastLong(message(t))); return null; });
    }

    private void showTransactions() {
        Wallet w = manager.wallet();
        LinearLayout box = page();
        box.addView(title(s(R.string.transactions)));
        if (w == null || w.getTransactions(false).isEmpty()) {
            box.addView(text(s(R.string.no_transactions), 15, textSecondaryColor()));
        } else {
            for (org.bitcoinj.core.Transaction tx : w.getTransactions(false)) {
                box.addView(text(tx.getTxId().toString() + "\n" + tx.getValueSentFromMe(w).toPlainString() + " BTC", 14, textPrimaryColor()));
            }
        }
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void showSettings() {
        Wallet w = manager.wallet();
        if (w == null) { toast(s(R.string.wallet_not_ready)); return; }
        LinearLayout box = page();
        box.addView(title(s(R.string.wallet_settings)));
        box.addView(text(getString(R.string.network_value, selectedNetwork.name()), 14, textSecondaryColor()));
        box.addView(actionButton(s(R.string.recovery_seed), s(R.string.show_mnemonic_words), v -> showSeed(w)));
        box.addView(actionButton(w.isEncrypted() ? s(R.string.decrypt_wallet) : s(R.string.set_password), s(R.string.change_wallet_encryption), v -> toggleEncryption(w)));
        box.addView(actionButton(s(R.string.restore_wallet), s(R.string.restore_from_mnemonic), v -> showRestore()));
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void showSeed(Wallet w) {
        try {
            DeterministicSeed seed = w.getKeyChainSeed();
            if (seed.isEncrypted()) {
                askPassword(s(R.string.wallet_password), pass -> {
                    try {
                        if (!w.checkPassword(pass)) throw new IllegalArgumentException(s(R.string.wrong_password));
                        AesKey key = w.getKeyCrypter().deriveKey(pass);
                        showSeedText(seed.decrypt(w.getKeyCrypter(), "", key));
                    } catch (Throwable t) { toast(message(t)); }
                });
            } else showSeedText(seed);
        } catch (Throwable t) { toast(message(t)); }
    }

    private void showSeedText(DeterministicSeed seed) {
        String words = seed.getMnemonicCode() == null ? seed.toString() : String.join(" ", seed.getMnemonicCode());
        new AlertDialog.Builder(this).setTitle(s(R.string.recovery_seed))
                .setMessage(words)
                .setPositiveButton(s(R.string.close), null)
                .setNegativeButton(s(R.string.copy), (d, w) -> copy(words)).show();
    }

    private void toggleEncryption(Wallet w) {
        if (w.isEncrypted()) {
            askPassword(s(R.string.decrypt_wallet), pass -> {
                try {
                    if (!w.checkPassword(pass)) throw new IllegalArgumentException(s(R.string.wrong_password));
                    w.decrypt(w.getKeyCrypter().deriveKey(pass));
                    toast(s(R.string.wallet_decrypted)); showSettings();
                } catch (Throwable t) { toast(message(t)); }
            });
        } else {
            askTwoPasswords(s(R.string.set_wallet_password), (a, b) -> {
                if (!a.equals(b) || a.length() < 4) { toast(s(R.string.password_requirements)); return; }
                try {
                    KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
                    w.encrypt(scrypt, scrypt.deriveKey(a));
                    toast(s(R.string.wallet_encrypted)); showSettings();
                } catch (Throwable t) { toast(message(t)); }
            });
        }
    }

    private void showRestore() {
        LinearLayout box = page();
        box.addView(title(s(R.string.restore_wallet)));
        EditText words = field(s(R.string.mnemonic_12_words));
        EditText date = field(s(R.string.birthday_utc));
        box.addView(label(s(R.string.recovery_words))); box.addView(words);
        box.addView(label(s(R.string.birthday))); box.addView(date);
        box.addView(actionButton(s(R.string.restore_and_resync), s(R.string.replace_current_wallet), v -> {
            try {
                if (wBalance() > 0) throw new IllegalStateException(s(R.string.wallet_must_be_empty));
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
        box.addView(title(s(R.string.wallet_tool)));
        box.addView(text(s(R.string.advanced_operations_this_wallet), 14, textSecondaryColor()));
        box.addView(text(getString(R.string.network_value, selectedNetwork.name()), 14, textSecondaryColor()));
        box.addView(section(s(R.string.common)));
        toolAction(box, s(R.string.inspect_wallet), s(R.string.show_wallet_details), "dump");
        toolAction(box, s(R.string.sync_wallet), s(R.string.download_transactions), "sync");
        toolAction(box, s(R.string.receive_address), s(R.string.get_receive_address), "current-receive-addr");
        toolAction(box, s(R.string.send_btc), s(R.string.create_broadcast_transaction), "send");
        box.addView(section(s(R.string.wallet)));
        toolAction(box, s(R.string.create_wallet), s(R.string.create_wallet_file), "create");
        toolAction(box, s(R.string.encrypt_wallet), s(R.string.encrypt_this_wallet), "encrypt");
        toolAction(box, s(R.string.decrypt_wallet), s(R.string.decrypt_this_wallet), "decrypt");
        toolAction(box, s(R.string.add_address), s(R.string.add_watching_address), "add-addr");
        toolAction(box, s(R.string.add_key), s(R.string.add_private_public_key), "add-key");
        toolAction(box, s(R.string.delete_key), s(R.string.remove_key_address), "delete-key");
        toolAction(box, s(R.string.reset_wallet), s(R.string.reset_wallet_desc), "reset");
        box.addView(section(s(R.string.advanced)));
        toolAction(box, s(R.string.raw_dump), s(R.string.raw_dump_desc), "raw-dump");
        toolAction(box, s(R.string.upgrade_wallet), s(R.string.upgrade_wallet_desc), "upgrade");
        toolAction(box, s(R.string.rotate_keys), s(R.string.rotate_keys_desc), "rotate");
        toolAction(box, s(R.string.set_creation_time), s(R.string.set_creation_time_desc), "set-creation-time");
        box.addView(backButton());
        setContentView(paddedScroll(box));
    }

    private void toolAction(LinearLayout box, String name, String desc, String action) {
        box.addView(actionButton(name, desc, v -> showToolAction(action)));
    }

    private void showToolAction(String action) {
        LinearLayout box = page();
        box.addView(title(toolTitle(action)));
        box.addView(text(getString(R.string.network_value, selectedNetwork.name()), 14, textSecondaryColor()));
        List<EditText> fields = new ArrayList<>();
        CheckBox force = null, allow = null, dumpPriv = null, dumpLook = null, offline = null;

        switch (action) {
            case "dump":
                fields.add(addField(box, s(R.string.password_if_encrypted), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                dumpPriv = check(s(R.string.show_private_keys_seed)); box.addView(dumpPriv);
                dumpLook = check(s(R.string.show_lookahead_keys)); box.addView(dumpLook);
                break;
            case "create":
                fields.add(addField(box, s(R.string.mnemonic_seed_optional), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.watch_xpub_optional), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.birthday_date), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.script_type), InputType.TYPE_CLASS_TEXT));
                force = check(s(R.string.force_existing_wallet)); box.addView(force);
                break;
            case "add-key":
                fields.add(addField(box, s(R.string.private_key), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.public_key_optional), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.creation_date_optional), InputType.TYPE_CLASS_TEXT));
                break;
            case "add-addr":
                fields.add(addField(box, s(R.string.bitcoin_address), InputType.TYPE_CLASS_TEXT));
                break;
            case "delete-key":
                fields.add(addField(box, s(R.string.public_key_or_address), InputType.TYPE_CLASS_TEXT));
                break;
            case "encrypt":
            case "decrypt":
                fields.add(addField(box, s(R.string.wallet_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                break;
            case "send":
                fields.add(addField(box, s(R.string.recipient_address), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.amount_btc_or_all), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.fee_sat_vbyte_optional), InputType.TYPE_CLASS_TEXT));
                fields.add(addField(box, s(R.string.wallet_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                allow = check(s(R.string.allow_unconfirmed_outputs)); box.addView(allow);
                offline = check(s(R.string.offline_transaction_only)); box.addView(offline);
                break;
            case "sync":
                force = check(s(R.string.force_reset_chain)); box.addView(force);
                break;
            case "rotate":
                fields.add(addField(box, s(R.string.rotation_date), InputType.TYPE_CLASS_TEXT));
                break;
            case "set-creation-time":
                fields.add(addField(box, s(R.string.creation_date_clear), InputType.TYPE_CLASS_TEXT));
                break;
            case "upgrade":
                fields.add(addField(box, s(R.string.script_type), InputType.TYPE_CLASS_TEXT));
                break;
            case "reset":
                force = check(s(R.string.force_reset)); box.addView(force);
                break;
            default:
                break;
        }

        TextView result = text("", 13, textSecondaryColor()); result.setTextIsSelectable(true);
        final CheckBox forceRef = force;
        final CheckBox allowRef = allow;
        final CheckBox offlineRef = offline;
        final CheckBox dumpPrivRef = dumpPriv;
        final CheckBox dumpLookRef = dumpLook;
        final List<EditText> fieldsRef = fields;
        final Button run = actionButton(s(R.string.run), s(R.string.execute_tool_action), null);
        run.setOnClickListener(v -> {
            List<String> args = new ArrayList<>(); args.add(action); args.add("--net=" + selectedNetwork.name());
            buildToolArgs(action, fieldsRef, forceRef, allowRef, offlineRef, dumpPrivRef, dumpLookRef, args);
            run.setEnabled(false); result.setText(s(R.string.running));
            boolean restart = manager.wallet() != null && manager.network() == selectedNetwork;
            if (restart) manager.stop();
            toolRunner.run(args, selectedNetwork, new ToolRunner.Callback() {
                @Override public void onStarted() { runOnUiThread(() -> result.setText(getString(R.string.running_wallet_file, manager.walletFile(selectedNetwork).getName()))); }
                @Override public void onFinished(int code, String output) { runOnUiThread(() -> { run.setEnabled(true); result.setText(getString(R.string.exit_code_result, code, output.isEmpty() ? s(R.string.no_output) : output)); if (restart) manager.start(selectedNetwork, walletListener()); }); }
                @Override public void onFailed(Throwable error) { runOnUiThread(() -> { run.setEnabled(true); result.setText(getString(R.string.failed_result, message(error))); if (restart) manager.start(selectedNetwork, walletListener()); }); }
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
        switch (a) { case "dump": return s(R.string.inspect_wallet); case "sync": return s(R.string.sync_wallet); case "current-receive-addr": return s(R.string.receive_address); case "send": return s(R.string.send_btc); case "add-addr": return s(R.string.add_address); case "add-key": return s(R.string.add_key); case "delete-key": return s(R.string.delete_key); case "raw-dump": return s(R.string.raw_dump); case "set-creation-time": return s(R.string.set_creation_time); default: return Character.toUpperCase(a.charAt(0)) + a.substring(1); }
    }

    private void askPassword(String title, PasswordCallback cb) { EditText input = field(s(R.string.password)); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton(s(R.string.ok), (d,w) -> cb.accept(input.getText().toString())).setNegativeButton(s(R.string.cancel), null).show(); }
    private void askTwoPasswords(String title, TwoPasswordCallback cb) { LinearLayout box = page(); EditText a = field(s(R.string.password)); EditText b = field(s(R.string.repeat_password)); a.setInputType(129); b.setInputType(129); box.addView(a); box.addView(b); new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton(s(R.string.ok), (d,w) -> cb.accept(a.getText().toString(), b.getText().toString())).setNegativeButton(s(R.string.cancel), null).show(); }
    private interface PasswordCallback { void accept(String password); }
    private interface TwoPasswordCallback { void accept(String a, String b); }

    private LinearLayout page() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(24, 20, 24, 28); return l; }
    private ScrollView paddedScroll(View child) { ScrollView s = new ScrollView(this); s.setFillViewport(true); s.addView(child); return s; }
    private TextView title(String value) { return text(value, 26, textPrimaryColor()); }
    private TextView bigText(String value) { TextView t = text(value, 23, textPrimaryColor()); t.setPadding(0, 8, 0, 16); return t; }
    private TextView section(String value) { TextView t = text(value, 12, accentColor()); t.setPadding(0, 22, 0, 7); return t; }
    private TextView label(String value) { TextView t = text(value, 11, textSecondaryColor()); t.setPadding(0, 12, 0, 2); return t; }
    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, 7, 0, 7); return t; }
    private Button actionButton(String name, String desc, View.OnClickListener listener) { Button b = new Button(this); b.setText(desc.isEmpty() ? name : name + "\n" + desc); b.setTextSize(15); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); b.setAllCaps(false); b.setOnClickListener(listener); b.setMinHeight(64); return b; }
    private Button backButton() { return actionButton(s(R.string.back), s(R.string.return_to_wallet), v -> showWalletScreen()); }
    private Button button(String s) { return actionButton(s, "", null); }
    private Spinner spinner(String[] values, String selected) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); s.setSelection(Arrays.asList(values).indexOf(selected)); return s; }
    private EditText field(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setTextSize(16); e.setSingleLine(true); e.setPadding(0, 10, 0, 10); return e; }
    private CheckBox check(String label) { CheckBox c = new CheckBox(this); c.setText(label); c.setAllCaps(false); return c; }
    private void copy(String value) { ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText(s(R.string.bitcoin), value)); toast(s(R.string.copied)); }
    private String s(int id) { return getString(id); }
    private int resolveColor(int attr) { android.util.TypedValue value = new android.util.TypedValue(); getTheme().resolveAttribute(attr, value, true); return value.resourceId != 0 ? getColor(value.resourceId) : value.data; }
    private int textPrimaryColor() { return resolveColor(android.R.attr.textColorPrimary); }
    private int textSecondaryColor() { return resolveColor(android.R.attr.textColorSecondary); }
    private int accentColor() { return resolveColor(android.R.attr.colorAccent); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void toastLong(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private Bitmap qr(String value, int size) { try { BitMatrix m = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size); Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565); for (int x=0;x<size;x++) for(int y=0;y<size;y++) b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE); return b; } catch(Exception e){ throw new IllegalStateException(e); } }
    private String message(Throwable t) { Throwable x=t; while(x.getCause()!=null&&x.getCause()!=x)x=x.getCause(); return x.getMessage()==null?x.toString():x.getMessage(); }

    @Override protected void onDestroy() { toolRunner.shutdown(); manager.stop(); super.onDestroy(); }
}
