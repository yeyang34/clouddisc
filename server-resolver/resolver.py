#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
歪歪网易云唱片 CloudDisc · 自建解析服务（专用机版）

用途
    用【你自己的】网易云账号，把歌曲 id 换成可播放地址，供 CloudDisc 客户端使用。
    这台机器与"我的世界服务器是谁的"完全无关：客户端只是来请求这个 HTTP 地址。

设计约束（为了能长期稳定地跑在一台小机器上）
    · 只用 Python 标准库，不需要 pip 安装任何东西
    · **完全不落盘**：不回写任何文件；解析结果只在内存里记几分钟
    · 内存占用极小（无缓存体量、无第三方框架）；实测常驻 ~30MB
    · 只做两件事：换地址（/song）、必要时转发音频流（/stream）

接口
    GET /health                      健康检查（不需要令牌，只回一句 JSON）
    GET /song/<id>?token=XXX         返回 {"title":"歌名 - 歌手","url":"http://..."}
    GET /stream/<id>?token=XXX       直接回音频字节流（当 CDN 地址对玩家不可用时用）

配置（见 /etc/clouddisc-resolver/）
    cookie   你的网易云 Cookie（形如 MUSIC_U=xxxx; __csrf=yyyy，或只写 MUSIC_U 的值）
    token    访问令牌（客户端配置里带上；建议 32 位以上随机串）

环境变量
    CDR_PORT      监听端口（默认 8787）
    CDR_MODE      地址模式：auto（默认，先给 CDN 地址）/ proxy（一律走本机转发）/ direct（只给 CDN 地址）
    CDR_ALLOW     可选。IP 白名单，逗号分隔，支持 CIDR，例如 1.2.3.4,10.0.0.0/8
    CDR_RATE      每个 IP 每分钟最多请求数（默认 60）

本服务不做的事
    · 不内置任何账号或共享凭据；不使用第三方私有接口
    · 不解密 .ncm/.uc 等受保护格式
    · 不缓存音频到磁盘（要缓存请让玩家各自的客户端缓存）
"""

import hmac
import ipaddress
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE_FILE = "/etc/clouddisc-resolver/cookie"
TOKEN_FILE = "/etc/clouddisc-resolver/token"

PORT = int(os.environ.get("CDR_PORT", "8787"))
MODE = os.environ.get("CDR_MODE", "auto").strip().lower()
ALLOW_RAW = os.environ.get("CDR_ALLOW", "").strip()
RATE_PER_MIN = int(os.environ.get("CDR_RATE", "60"))
# 本机上运行的社区解析项目（NeteaseCloudMusicApi）。它负责跟网易接口的变化，
# 我们只当薄适配层：加令牌、限流、统一成 {"title","url"} 给 mod。
CDR_API = os.environ.get("CDR_API", "http://127.0.0.1:3300").rstrip("/")
CDR_LEVEL = os.environ.get("CDR_LEVEL", "exhigh").strip()   # standard/higher/exhigh/lossless/hires

OUTER = "https://music.163.com/song/media/outer/url?id={id}.mp3"
META = "https://music.163.com/api/song/detail/?id={id}&ids=[{id}]"
UA = "Mozilla/5.0 (compatible; CloudDisc-Resolver)"
ID_RE = re.compile(r"^\d{1,12}$")
MEMO_TTL = 300.0          # 解析结果在内存里记 5 分钟
UPSTREAM_TIMEOUT = 12.0

_memo = {}
_memo_lock = threading.Lock()
_rate = {}
_rate_lock = threading.Lock()
_cookie_cache = {"mtime": 0.0, "value": ""}


def log(*a):
    """所有日志都走这里；**绝不打印 cookie 或完整 token**。"""
    print("[clouddisc-resolver]", *a, flush=True)


def load_cookie():
    """每次按修改时间懒加载，改完文件不用重启服务。"""
    try:
        mtime = os.path.getmtime(COOKIE_FILE)
    except OSError:
        return ""
    with _memo_lock:
        if _cookie_cache["mtime"] != mtime:
            try:
                with open(COOKIE_FILE, "r", encoding="utf-8", errors="replace") as f:
                    raw = f.read().strip()
            except OSError:
                raw = ""
            if raw and "=" not in raw.split(";")[0]:
                raw = "MUSIC_U=" + raw          # 只填了值的情况
            _cookie_cache["mtime"] = mtime
            _cookie_cache["value"] = raw
            log("已加载 cookie（长度 %d）" % len(raw) if raw else "cookie 为空")
        return _cookie_cache["value"]


def load_token():
    try:
        with open(TOKEN_FILE, "r", encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def allow_list():
    out = []
    for part in ALLOW_RAW.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            log("CDR_ALLOW 里有一项无法解析，已忽略：%s" % part)
    return out


ALLOW = allow_list()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_no_redirect = urllib.request.build_opener(NoRedirect)


def upstream(url, cookie, redirect=False, extra_headers=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Referer", "https://music.163.com/")
    if cookie:
        req.add_header("Cookie", cookie)
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)
    opener = urllib.request.urlopen if redirect else _no_redirect.open
    return opener(req, timeout=UPSTREAM_TIMEOUT)


def fetch_meta(song_id, cookie):
    try:
        with upstream(META.format(id=song_id), cookie, redirect=True) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        songs = data.get("songs") or []
        if not songs:
            return None
        s = songs[0]
        name = (s.get("name") or "").strip()
        artists = s.get("artists") or []
        artist = (artists[0].get("name") or "").strip() if artists else ""
        if name and artist:
            return "%s - %s" % (name, artist)
        return name or None
    except Exception as e:
        log("查元数据失败 id=%s: %s" % (song_id, e))
        return None


def resolve_via_api(song_id, cookie):
    """优先走本机那个社区解析项目（含 VIP）。拿不到就返回 None，由调用方退回匿名外链。"""
    if not CDR_API:
        return None
    try:
        q = urllib.parse.urlencode({"id": song_id, "level": CDR_LEVEL, "cookie": cookie})
        with urllib.request.urlopen(CDR_API + "/song/url/v1?" + q, timeout=UPSTREAM_TIMEOUT) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        item = (data.get("data") or [{}])[0]
        url = item.get("url")
        if url:
            return url
        log("社区接口判定不可播 id=%s code=%s（可能是下架/无版权/需要购买专辑）" % (song_id, item.get("code")))
    except Exception as e:
        log("社区接口调用失败 id=%s: %s" % (song_id, e))
    return None


def resolve(song_id, base_url):
    """返回 (title, url)。url 可能是 CDN 直链，也可能是本机的 /stream 地址。"""
    cookie = load_cookie()
    now = time.time()
    with _memo_lock:
        hit = _memo.get(song_id)
        if hit and hit[2] > now:
            return hit[0], hit[1]

    # ① 先问本机的社区解析项目（VIP 走这条）
    direct = resolve_via_api(song_id, cookie)
    if direct:
        title = fetch_meta(song_id, cookie) or ("网易云 #" + song_id)
        url = direct
        with _memo_lock:
            _memo[song_id] = (title, url, now + MEMO_TTL)
        return title, url

    # ② 退回"匿名外链"（不需要登录就能播的曲目）
    direct = None
    try:
        with upstream(OUTER.format(id=song_id), cookie, redirect=False) as r:
            ctype = (r.headers.get("Content-Type") or "").lower()
            if 300 <= r.status < 400:
                loc = r.headers.get("Location") or ""
                # 网易把"不可匿名播放"跳到 /404 页面；有效地址则跳到 CDN
                if loc.startswith("http") and "/404" not in loc:
                    direct = loc
                else:
                    return None, None
            elif r.status == 200 and ("audio" in ctype or "octet-stream" in ctype):
                direct = None      # 直接就回音频了 → 必须由本机转发（客户端没有 cookie）
            elif r.status == 200:
                return None, None  # 200 + html/json = 没权限
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            loc = e.headers.get("Location") or ""
            if loc.startswith("http") and "/404" not in loc:
                direct = loc
            else:
                return None, None
        else:
            log("解析失败 id=%s HTTP %s" % (song_id, e.code))
            return None, None
    except Exception as e:
        log("解析异常 id=%s: %s" % (song_id, e))
        return None, None

    title = fetch_meta(song_id, cookie) or ("网易云 #" + song_id)
    if MODE == "proxy" or direct is None:
        url = "%s/stream/%s?token=%s" % (base_url, song_id, urllib.parse.quote(load_token()))
    else:
        url = direct
    with _memo_lock:
        _memo[song_id] = (title, url, now + MEMO_TTL)
    return title, url


class Handler(BaseHTTPRequestHandler):
    server_version = "CloudDiscResolver/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 默认的访问日志会打印完整 URL（含 token），这里全部屏蔽

    # ---------------------------------------------------------------- 工具

    def client_ip(self):
        # 反向代理场景下取 X-Forwarded-For 的第一段
        xff = self.headers.get("X-Forwarded-For")
        if xff:
            return xff.split(",")[0].strip()
        return self.client_address[0]

    def ip_allowed(self):
        if not ALLOW:
            return True
        try:
            ip = ipaddress.ip_address(self.client_ip())
        except ValueError:
            return False
        return any(ip in net for net in ALLOW)

    def rate_ok(self):
        if RATE_PER_MIN <= 0:
            return True
        now = time.time()
        ip = self.client_ip()
        with _rate_lock:
            win = _rate.setdefault(ip, [])
            win[:] = [t for t in win if now - t < 60.0]
            if len(win) >= RATE_PER_MIN:
                return False
            win.append(now)
        return True

    def token_ok(self):
        want = load_token()
        if not want:
            return False           # 没设令牌就一律拒绝，避免"裸奔"
        got = ""
        q = urllib.parse.urlparse(self.path).query
        params = urllib.parse.parse_qs(q)
        if params.get("token"):
            got = params["token"][0]
        elif self.headers.get("X-Token"):
            got = self.headers.get("X-Token")
        return hmac.compare_digest(got, want)

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def base_url(self):
        host = self.headers.get("Host") or ("127.0.0.1:%d" % PORT)
        return "http://" + host

    # ---------------------------------------------------------------- 路由

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/") or "/"

        if path == "/health":
            self.send_json(200, {"ok": True, "cookie": bool(load_cookie()),
                                 "mode": MODE, "ts": int(time.time())})
            return

        if not self.ip_allowed():
            self.send_json(403, {"error": "ip not allowed"})
            return
        if not self.rate_ok():
            self.send_json(429, {"error": "too many requests"})
            return
        if not self.token_ok():
            self.send_json(403, {"error": "bad token"})
            return

        m = re.match(r"^/(song|stream)/(\d{1,12})$", path)
        if not m:
            self.send_json(404, {"error": "not found"})
            return
        action, song_id = m.group(1), m.group(2)
        if not ID_RE.match(song_id):
            self.send_json(400, {"error": "bad id"})
            return

        try:
            if action == "song":
                title, url = resolve(song_id, self.base_url())
                if not url:
                    self.send_json(404, {"error": "not playable (VIP/exclusive/region-locked, or cookie expired)"})
                    return
                log("已解析 id=%s" % song_id)
                self.send_json(200, {"title": title, "url": url})
                return
            self.stream(song_id)
        except BrokenPipeError:
            pass
        except Exception as e:
            log("处理失败 %s: %s" % (path, e))
            try:
                self.send_json(500, {"error": "internal"})
            except Exception:
                pass

    def stream(self, song_id):
        """把音频流原样转发给客户端（不落盘）。客户端没有 cookie，所以只能这样给它。"""
        cookie = load_cookie()
        title, url = resolve(song_id, self.base_url())
        if not url:
            self.send_json(404, {"error": "not playable"})
            return
        # 如果解析出来的就是本机 /stream 地址，说明直链不可用 → 先去拿真正的 CDN 地址
        if "/stream/" in url:
            target = None
            try:
                with upstream(OUTER.format(id=song_id), cookie, redirect=False) as r:
                    if 300 <= r.status < 400:
                        target = r.headers.get("Location")
            except urllib.error.HTTPError as e:
                target = e.headers.get("Location") if e.code in (301, 302, 303, 307, 308) else None
            except Exception:
                target = None
            if not target:
                self.send_json(404, {"error": "no upstream url"})
                return
            url = target

        with upstream(url, cookie, redirect=True) as r:
            self.send_response(200)
            self.send_header("Content-Type", r.headers.get("Content-Type") or "audio/mpeg")
            length = r.headers.get("Content-Length")
            if length:
                self.send_header("Content-Length", length)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            total = 0
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
                total += len(chunk)
        log("已转发 id=%s（%d 字节）" % (song_id, total))


def main():
    if not load_token():
        log("警告：%s 不存在或为空 —— 出于安全，所有带令牌的接口都会拒绝访问。" % TOKEN_FILE)
    if not load_cookie():
        log("提示：%s 还没有填 cookie → 目前只能解析【匿名可播】的曲目。" % COOKIE_FILE)
    log("监听 0.0.0.0:%d  mode=%s  rate=%d/min  allow=%s"
        % (PORT, MODE, RATE_PER_MIN, ALLOW_RAW or "不限"))
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
