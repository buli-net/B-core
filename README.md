# BitcoinJ Android Wallet + Wallet Tool

This project converts the supplied desktop `wallettemplate` and `wallettool` sources into one native Android application.

## Included

- bitcoinj-core 0.17.1
- Native Android wallet UI
- P2WPKH wallet using BIP43 key-chain structure
- Mainnet, Testnet, Signet and Regtest selection
- Background SPV synchronization
- Receive address + QR
- Send BTC and empty-wallet sends
- Encrypted wallet / password protection
- Decrypt wallet
- Mnemonic seed display
- Restore from mnemonic + birthday
- The supplied `wallet-tool` actions in the same app:
  - dump
  - raw-dump
  - create
  - add-key
  - add-addr
  - delete-key
  - current-receive-addr
  - sync
  - reset
  - send
  - encrypt
  - decrypt
  - upgrade
  - rotate
  - set-creation-time
- Wallet-tool options are exposed as Android form fields and checkboxes.
- GitHub Actions builds the debug APK and uploads it as an artifact.

## Important storage note

Android app-private storage is used. The normal wallet is under:

`files/bitcoinj/`

The tool's default wallet and chain files are under the same directory.

## Build

The GitHub Actions workflow is manual or runs on pushes to `master`.

For a local build with Gradle 8.9 and JDK 17:

```bash
gradle :app:assembleDebug --no-daemon
```

The resulting APK is:

`app/build/outputs/apk/debug/app-debug.apk`
