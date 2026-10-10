#!/usr/bin/env python3
"""Simula al admin de un grupo (otra persona) para la prueba en emulador.

  fake_admin.py new ARCHIVO          crea código, grupo y claves; los guarda en ARCHIVO (JSON)
  fake_admin.py sign ARCHIVO TIPO CUERPO_JSON
                                     imprime un mensaje de Friend Game firmado como lo firma la app

Firma igual que la app: ECDSA P-256 con SHA-256, firma DER y claves en base64 url sin relleno.
"""
import base64
import json
import random
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def code() -> str:
    return "".join(random.choice(ALPHABET) for _ in range(16))


def main():
    cmd, path = sys.argv[1], sys.argv[2]
    if cmd == "new":
        key = ec.generate_private_key(ec.SECP256R1())
        public = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        secret = key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        ).decode()
        data = {"code": code(), "group": code(), "key": b64(public), "secret": secret}
        with open(path, "w") as f:
            json.dump(data, f)
        print(json.dumps({k: data[k] for k in ("code", "group", "key")}))
        return
    if cmd == "sign":
        kind, body = sys.argv[3], sys.argv[4]
        with open(path) as f:
            data = json.load(f)
        key = serialization.load_pem_private_key(data["secret"].encode(), password=None)
        sig = key.sign(body.encode(), ec.ECDSA(hashes.SHA256()))
        print(json.dumps({"fg": 1, "t": kind, "from": data["code"], "name": "Eva", "body": body, "sig": b64(sig)}))
        return
    print(__doc__)
    sys.exit(2)


if __name__ == "__main__":
    main()
