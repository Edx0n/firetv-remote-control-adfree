#!/usr/bin/env python3
"""Controle o Fire TV pelo PC (mesma rede), com a mesma API do app oficial da Amazon.

Só usa a biblioteca padrão do Python. Exemplos:

    python firetv.py discover
    python firetv.py pair 192.168.0.20          # mostra um PIN na TV e pede para digitar aqui
    python firetv.py key home                    # dpad_up, dpad_down, dpad_left, dpad_right, select,
                                                 # back, home, menu, volume_up, volume_down, mute,
                                                 # sleep, power, backspace
    python firetv.py media play                  # play (alterna pausa), rewind, ff
    python firetv.py apps                        # lista os apps instalados
    python firetv.py launch com.netflix.ninja    # abre um app pelo appId
    python firetv.py text "the office"           # digita no campo de texto aberto na TV
    python firetv.py raw GET /v1/FireTV/properties

O token do pareamento fica salvo em ~/.firetv_remote.json. Detalhes em docs/PROTOCOLO.md.
"""
import argparse
import json
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API_PORT = 8080
API_KEY = "0987654321"  # chave fixa embutida no app oficial (e nos apps de terceiros)
CONFIG = Path.home() / ".firetv_remote.json"

_SSL = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE  # a TV usa um certificado da CA interna da Amazon


def load_config():
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    CONFIG.write_text(json.dumps(cfg, indent=2), encoding="utf-8")


def http(method, url, body=None, headers=None, timeout=10.0):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_SSL if url.startswith("https") else None) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


class FireTv:
    def __init__(self, host, token=None):
        self.host = host
        self.token = token
        self.base = f"https://{host}:{API_PORT}"

    def headers(self, auth=True):
        h = {
            "x-amzn-request-id": str(uuid.uuid4()),
            "Content-Type": "application/json; charset=utf-8",
            "x-api-key": API_KEY,
        }
        if auth and self.token:
            h["x-client-token"] = self.token
        return h

    def wake(self):
        """POST DIAL que (re)inicia o serviço de controle da TV (porta 8080)."""
        status, _ = http("POST", f"http://{self.host}:8009/apps/FireTVRemote", body=b"",
                         headers={"Content-Type": "text/plain", "x-amzn-request-id": str(uuid.uuid4())}, timeout=10)
        time.sleep(0.2)
        return status

    def call(self, method, path, body=None, auth=True, timeout=10.0, retry=True):
        payload = None if method == "GET" else (b"" if body is None else body)
        try:
            return http(method, self.base + path, payload, self.headers(auth), timeout)
        except (OSError, urllib.error.URLError):
            if not retry:
                raise
            self.wake()
            return self.call(method, path, body, auth, timeout, retry=False)

    def status(self):
        status, text = self.call("GET", "/v1/FireTV/status")
        try:
            return json.loads(text).get("turnstileVersion", "") if status < 400 else ""
        except ValueError:
            return ""

    def key(self, action, key_action_type=None):
        if action == "home":
            self.call("POST", "/v1/FireTV?action=wakeup", timeout=3)
        body = {"keyActionType": key_action_type} if key_action_type else None
        return self.call("POST", f"/v1/FireTV?action={action}", body, timeout=3)

    def media(self, what):
        if what == "play":
            return self.call("POST", "/v1/media?action=play")
        direction = {"rewind": "back", "ff": "forward"}[what]
        # O app oficial manda os números como texto.
        return self.call("POST", "/v1/media?action=scan",
                         {"direction": direction, "durationInSeconds": "10", "speed": "1"})

    def type_text(self, text):
        status, body = self.call("GET", "/v1/FireTV/keyboard")
        state = json.loads(body).get("state") if status < 400 else "hidden"
        if state in ("visible", "text"):
            return self.call("POST", "/v1/FireTV/keyboard", {"text": text})
        result = (200, "")
        for ch in text:  # TVs antigas: uma letra por vez
            result = self.call("POST", "/v1/FireTV/text", {"text": ch})
        return result


def ssdp_discover(timeout=4.0):
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
           "MX: 1\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n").encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0.3)
    found, end, next_search = {}, time.time() + timeout, 0
    while time.time() < end:
        if time.time() >= next_search:
            s.sendto(msg, ("239.255.255.250", 1900))
            next_search = time.time() + 1
        try:
            data, addr = s.recvfrom(4096)
        except socket.timeout:
            continue
        m = re.search(r"(?im)^location:\s*(\S+)", data.decode(errors="replace"))
        if m and addr[0] not in found:
            found[addr[0]] = m.group(1)
    s.close()
    result = []
    for host, loc in found.items():
        try:
            if http("GET", f"http://{host}:8009/apps/FireTVRemote", timeout=3)[0] >= 400:
                continue
            _, xml = http("GET", loc, timeout=3)
        except OSError:
            continue
        tag = lambda t: (re.search(rf"<{t}>(.*?)</{t}>", xml, re.S) or [None, ""])[1]
        result.append((host, tag("friendlyName"), tag("modelName")))
    return result


def show(status, text):
    try:
        text = json.dumps(json.loads(text), indent=2, ensure_ascii=False)
    except ValueError:
        pass
    print(f"HTTP {status}")
    if text:
        print(text)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", help="IP do Fire TV (padrão: o último pareado)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("discover", help="procura Fire TVs na rede (SSDP/DIAL)")
    sp = sub.add_parser("pair", help="pareia por PIN e salva o token")
    sp.add_argument("ip")
    sp.add_argument("--name", default="Controle Fire TV (PC)")
    sp.add_argument("--pin", help="só confirma um PIN já exibido na TV (uso não interativo)")
    sp.add_argument("--show-only", action="store_true", help="só exibe o PIN na TV e sai")
    sk = sub.add_parser("key", help="envia uma tecla")
    sk.add_argument("action")
    sk.add_argument("--type", choices=["keyDown", "keyUp"])
    sm = sub.add_parser("media", help="play | rewind | ff")
    sm.add_argument("what", choices=["play", "rewind", "ff"])
    sub.add_parser("apps", help="lista apps instalados")
    sl = sub.add_parser("launch", help="abre um app pelo appId")
    sl.add_argument("app_id")
    st = sub.add_parser("text", help="digita no campo de texto aberto na TV")
    st.add_argument("text")
    sr = sub.add_parser("raw", help="requisição livre na API")
    sr.add_argument("method")
    sr.add_argument("path")
    sr.add_argument("body", nargs="?")
    sub.add_parser("wake", help="acorda o serviço via DIAL")
    args = p.parse_args()
    cfg = load_config()

    if args.cmd == "discover":
        for host, name, model in ssdp_discover():
            print(f"{host:16} {model:10} {name}")
        return

    if args.cmd == "pair":
        tv = FireTv(args.ip)
        if not args.pin:
            tv.wake()
            time.sleep(0.5)
            show(*tv.call("POST", "/v1/FireTV/pin/display", {"friendlyName": args.name}, auth=False, timeout=3))
            if args.show_only:
                return
        pin = args.pin or input("PIN exibido na TV: ").strip()
        status, text = tv.call("POST", "/v1/FireTV/pin/verify", {"pin": pin}, auth=False)
        show(status, text)
        token = json.loads(text).get("description") if status == 200 else None
        if token:
            cfg.setdefault("tokens", {})[args.ip] = token
            cfg["last"] = args.ip
            save_config(cfg)
            print(f"Pareado! Token salvo em {CONFIG}")
        else:
            print("PIN incorreto ou expirado.")
        return

    host = args.host or cfg.get("last")
    if not host:
        sys.exit("Informe --host IP ou pareie primeiro com: python firetv.py pair IP")
    tv = FireTv(host, cfg.get("tokens", {}).get(host))
    if args.cmd == "wake":
        print("DIAL:", tv.wake())
    elif args.cmd == "key":
        show(*tv.key(args.action, args.type))
    elif args.cmd == "media":
        show(*tv.media(args.what))
    elif args.cmd == "apps":
        path = "/v1/FireTV/appsV2" if tv.status() else "/v1/FireTV/apps"
        status, text = tv.call("GET", path)
        if status >= 400:
            show(status, text)
            return
        for app in json.loads(text):
            print(f"{app.get('appId', ''):45} {app.get('name', '')}")
    elif args.cmd == "launch":
        show(*tv.call("POST", f"/v1/FireTV/app/{args.app_id}"))
    elif args.cmd == "text":
        show(*tv.type_text(args.text))
    elif args.cmd == "raw":
        show(*tv.call(args.method.upper(), args.path, json.loads(args.body) if args.body else None))


if __name__ == "__main__":
    main()
