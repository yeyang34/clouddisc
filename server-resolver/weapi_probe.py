#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
weapi / eapi 变体探针：一次性试四种请求形态，打印原始响应，定位哪一种能拿到播放地址。
依赖：Python 标准库 + 系统 openssl。不装任何 pip 包。
"""
import base64
import json
import re
import secrets
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

NONCE = b"0CoJUm6Qyw8W8jud"
IV = "0102030405060708090a0b0c0d0e0f10"
PUB_N = ("00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76"
         "d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee2"
         "55932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7")
PUB_E = "010001"
EAPI_KEY = b"e82ckenh8dichen8"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
COOKIE_FILE = "/etc/clouddisc-resolver/cookie"


def sh(args, data):
    p = subprocess.run(args, input=data, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("openssl 失败: " + p.stderr.decode("utf-8", "replace")[:200])
    return p.stdout


def aes_cbc_b64(data: bytes, key: bytes) -> bytes:
    return sh(["openssl", "enc", "-aes-128-cbc", "-K", key.hex(), "-iv", IV, "-base64", "-A"], data).strip()


def aes_ecb_hex(data: bytes, key: bytes) -> str:
    return sh(["openssl", "enc", "-aes-128-ecb", "-K", key.hex(), "-nosalt"], data).hex()


def rsa_encrypt_hex(text: str) -> str:
    m = int.from_bytes(text[::-1].encode(), "big")
    return format(pow(m, int(PUB_E, 16), int(PUB_N, 16)), "0256x")


def get_csrf(cookie: str) -> str:
    m = re.search(r"__csrf=([0-9a-fA-F]+)", cookie)
    return m.group(1) if m else ""


def post(url: str, body: bytes, cookie: str, extra=None):
    headers = {
        "User-Agent": UA,
        "Referer": "https://music.163.com/",
        "Origin": "https://music.163.com",
        "Content-Type": "application/x-www-form-urlencoded",
        "Cookie": cookie,
    }
    if extra:
        headers.update(extra)
    req = urllib.request.Request(url, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return -1, str(e).encode()


def show(tag, status, raw):
    text = raw.decode("utf-8", "replace")
    print("[%s] HTTP %s  len=%d" % (tag, status, len(raw)))
    print("   " + text[:400].replace("\n", " "))


def weapi(cookie, csrf, path, payload):
    secret = base64.b64encode(secrets.token_bytes(16)).decode()[:16]
    raw = json.dumps(payload, separators=(",", ":")).encode()
    params = aes_cbc_b64(aes_cbc_b64(raw, NONCE), secret.encode()).decode()
    body = urllib.parse.urlencode({"params": params, "encSecKey": rsa_encrypt_hex(secret)}).encode()
    url = "https://music.163.com/weapi%s?csrf_token=%s" % (path, csrf)
    return post(url, body, cookie)


def eapi(cookie, path, payload):
    body = urllib.parse.urlencode({"params": aes_ecb_hex(json.dumps(payload, separators=(",", ":")).encode(), EAPI_KEY)}).encode()
    # X-Real-IP 用本机地址即可（当初为了排查"机房 IP 被拒"才加；结论是不需要它）
    return post("https://interface.music.163.com/eapi" + path, body, cookie,
                {"User-Agent": "NMI/1.0", "X-Real-IP": "127.0.0.1", "Cookie": cookie})


def main():
    song = sys.argv[1] if len(sys.argv) > 1 else "186016"
    with open(COOKIE_FILE, "r", encoding="utf-8", errors="replace") as f:
        cookie = f.read().strip()
    csrf = get_csrf(cookie)
    print("id=%s  cookie=%d 字节  csrf=%s" % (song, len(cookie), csrf or "(无)"))

    # A: weapi v1 + csrf 放 query + exhigh
    show("A weapi v1 exhigh", *weapi(cookie, csrf, "/song/enhance/player/url/v1",
                                     {"ids": "[%s]" % song, "level": "exhigh", "encodeType": "mp3", "csrf_token": csrf}))
    # B: weapi v1 + standard
    show("B weapi v1 standard", *weapi(cookie, csrf, "/song/enhance/player/url/v1",
                                       {"ids": "[%s]" % song, "level": "standard", "encodeType": "mp3", "csrf_token": csrf}))
    # C: weapi 旧版 player/url + br
    show("C weapi legacy br320", *weapi(cookie, csrf, "/song/enhance/player/url",
                                        {"ids": "[%s]" % song, "br": 320000, "csrf_token": csrf}))
    # D: eapi（官方客户端协议，AES-ECB）
    show("D eapi v1 exhigh", *eapi(cookie, "/song/enhance/player/url/v1",
                                   {"ids": "[%s]" % song, "level": "exhigh", "encodeType": "mp3"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
