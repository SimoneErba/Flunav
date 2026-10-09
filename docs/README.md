# Encrypted source files

The four source files are encrypted with AES-256-GCM. Their filenames do not reveal the original names. A cryptographic hash cannot be reversed; use the passphrase provided separately to decrypt these files. The passphrase is not stored in this repository.

From the repository root, install the Python `cryptography` package if needed, then run the restore script. It prompts for the passphrase and verifies each file before writing plaintext:

```sh
python3 -m pip install cryptography
python3 docs/restore_sources.py
```

The restored files are `SortingBase.cs`, `SortingCDG.cs`, `SortingDB.cs`, and `intermediate_destinations.sql` in `docs/`. They are plaintext and ignored by Git. Keep them local and delete them after use. Encrypting the current files does not remove plaintext from older Git commits; clean repository history separately before sharing the repository with anyone who must not access that history.

The encrypted file format is `FLUNAVDOC1` followed by a 16-byte salt, a 12-byte nonce, and AES-GCM ciphertext with its authentication tag. The key is derived from the passphrase with PBKDF2-HMAC-SHA256 and 600,000 iterations.
