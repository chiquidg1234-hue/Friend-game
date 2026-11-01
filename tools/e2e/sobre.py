#!/usr/bin/env python3
"""Cifrado de punta a punta de Friend Game, en Python (el mismo de Cifrado.kt), para la prueba.

  sobre.py keygen                                  imprime "PUBLICA PRIVADA" (base64 url)
  sobre.py seal PRIV PUB_AMIGO DE PARA JSON         imprime el sobre {"t":"enc",...} con la clave pública propia
  sobre.py open PRIV PUB_AMIGO DE PARA SOBRE        imprime el mensaje de adentro (sale con 1 si no abre)
  sobre.py inbox ARCHIVO PRIV MI_CODIGO TIPO [clave=valor ...] [--wait SEGUNDOS]
      busca en lo que le llegó al amigo falso un mensaje CIFRADO de ese tipo y lo imprime abierto

Necesita el paquete "cryptography".
"""
import base64
import json
import os
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

INFO = b"friendgame e2e v1"


def b64e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def keygen():
    key = ec.generate_private_key(ec.SECP256R1())
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    priv = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return b64e(pub), b64e(priv)


def public_of(priv: str) -> str:
    key = serialization.load_der_private_key(b64d(priv), None)
    return b64e(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


def shared_key(priv: str, friend_pub: str, me: str, friend: str) -> bytes:
    key = serialization.load_der_private_key(b64d(priv), None)
    other = serialization.load_der_public_key(b64d(friend_pub))
    secret = key.exchange(ec.ECDH(), other)
    salt = "|".join(sorted([me, friend])).encode()
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=INFO).derive(secret)


def aad(sender: str, to: str) -> bytes:
    return f"fg1|{sender}|{to}".encode()


def seal(priv, friend_pub, sender, to, inner: dict) -> dict:
    key = shared_key(priv, friend_pub, sender, to)
    nonce = os.urandom(12)
    sealed = AESGCM(key).encrypt(nonce, json.dumps(inner, ensure_ascii=False).encode(), aad(sender, to))
    return {"fg": 1, "t": "enc", "from": sender, "n": b64e(nonce), "c": b64e(sealed), "pk": public_of(priv)}


def open_(priv, friend_pub, sender, to, envelope: dict):
    key = shared_key(priv, friend_pub, sender, to)
    inner = json.loads(AESGCM(key).decrypt(b64d(envelope["n"]), b64d(envelope["c"]), aad(sender, to)))
    if inner.get("from") != sender:
        raise ValueError("remitente distinto")
    return inner


def inbox(path, priv, me, kind, wanted):
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        return None
    for line in lines:
        try:
            event = json.loads(line)
            if event.get("event") != "message":
                continue
            envelope = json.loads(event.get("message", ""))
            if envelope.get("t") != "enc":
                continue
            inner = open_(priv, envelope["pk"], envelope["from"], me, envelope)
        except Exception:
            continue
        if inner.get("t") == kind and all(str(inner.get(k)) == v for k, v in wanted.items()):
            return inner
    return None


def main():
    args = sys.argv[1:]
    cmd = args[0]
    if cmd == "keygen":
        print(*keygen())
        return 0
    if cmd == "seal":
        priv, pub, sender, to, text = args[1:6]
        print(json.dumps(seal(priv, pub, sender, to, json.loads(text)), ensure_ascii=False))
        return 0
    if cmd == "open":
        priv, pub, sender, to, text = args[1:6]
        try:
            print(json.dumps(open_(priv, pub, sender, to, json.loads(text)), ensure_ascii=False))
            return 0
        except Exception:
            return 1
    if cmd == "inbox":
        timeout = 0.0
        if "--wait" in args:
            i = args.index("--wait")
            timeout = float(args[i + 1])
            del args[i:i + 2]
        path, priv, me, kind, pairs = args[1], args[2], args[3], args[4], args[5:]
        wanted = dict(p.split("=", 1) for p in pairs)
        end = time.time() + timeout
        while True:
            found = inbox(path, priv, me, kind, wanted)
            if found is not None:
                print(json.dumps(found, ensure_ascii=False))
                return 0
            if time.time() >= end:
                return 1
            time.sleep(1)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
