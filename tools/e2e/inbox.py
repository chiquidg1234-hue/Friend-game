#!/usr/bin/env python3
"""Revisa lo que le llegó al "amigo falso" (salida de curl .../json guardada en un archivo).

  inbox.py ARCHIVO TIPO [clave=valor ...] [--wait SEGUNDOS]
  inbox.py ARCHIVO TIPO [clave=valor ...] --count

Sale con 0 si encuentra un mensaje de Friend Game de ese tipo con esos valores, e imprime su JSON.
Con --count imprime cuántos mensajes así llegaron.
"""
import json
import sys
import time


def messages(path, kind, wanted):
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        return
    for line in lines:
        try:
            event = json.loads(line)
            if event.get("event") != "message":
                continue
            payload = json.loads(event.get("message", ""))
        except (json.JSONDecodeError, TypeError):
            continue
        if payload.get("fg") != 1 or payload.get("t") != kind:
            continue
        if all(str(payload.get(k)) == v for k, v in wanted.items()):
            yield payload


def search(path, kind, wanted):
    return next(messages(path, kind, wanted), None)


def main():
    args = sys.argv[1:]
    if "--count" in args:
        args.remove("--count")
        path, kind, pairs = args[0], args[1], args[2:]
        print(sum(1 for _ in messages(path, kind, dict(p.split("=", 1) for p in pairs))))
        return 0
    timeout = 0.0
    if "--wait" in args:
        i = args.index("--wait")
        timeout = float(args[i + 1])
        del args[i:i + 2]
    path, kind, pairs = args[0], args[1], args[2:]
    wanted = dict(p.split("=", 1) for p in pairs)
    end = time.time() + timeout
    while True:
        found = search(path, kind, wanted)
        if found is not None:
            print(json.dumps(found, ensure_ascii=False))
            return 0
        if time.time() >= end:
            return 1
        time.sleep(1)


if __name__ == "__main__":
    sys.exit(main())
