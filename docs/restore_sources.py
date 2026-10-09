"""Restore the private source files using the separately supplied passphrase."""

from getpass import getpass
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes


ROOT = Path(__file__).resolve().parent
FILES = (
    ("source-01.cs.enc", "SortingBase.cs"),
    ("source-02.cs.enc", "SortingCDG.cs"),
    ("source-03.cs.enc", "SortingDB.cs"),
    ("source-04.sql.enc", "intermediate_destinations.sql"),
)
MAGIC = b"FLUNAVDOC1"


def decrypt(blob: bytes, passphrase: bytes) -> bytes:
    if len(blob) < len(MAGIC) + 16 + 12 + 16 or not blob.startswith(MAGIC):
        raise ValueError("Invalid encrypted source file")
    salt = blob[len(MAGIC) : len(MAGIC) + 16]
    nonce = blob[len(MAGIC) + 16 : len(MAGIC) + 28]
    ciphertext = blob[len(MAGIC) + 28 :]
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=600_000).derive(passphrase)
    return AESGCM(key).decrypt(nonce, ciphertext, MAGIC)


def main() -> None:
    passphrase = getpass("Source passphrase: ").encode()
    restored = []
    for encrypted, original in FILES:
        destination = ROOT / original
        if destination.exists():
            raise FileExistsError(f"Refusing to overwrite {destination}")
        restored.append((destination, decrypt((ROOT / encrypted).read_bytes(), passphrase)))
    for destination, content in restored:
        destination.write_bytes(content)
        print(f"Restored {destination}")


if __name__ == "__main__":
    main()
