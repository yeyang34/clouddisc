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
import subprocess
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


def fetch_dt(song_id, cookie):
    """歌曲总时长（毫秒）；取不到返回 0。客户端靠它决定"放到真正的结尾"。

    注意：这个值**必须可靠**，否则客户端会退化成"按唱片时长被掐断"。
    实测旧接口 api/song/detail 有时不返回 dt（返回 0），所以：
      ① 先问本机的社区项目（它的 song/detail 一定带 dt）
      ② 再退回旧接口
    """
    if CDR_API:
        try:
            q = urllib.parse.urlencode({"ids": song_id, "cookie": cookie})
            with urllib.request.urlopen(CDR_API + "/song/detail?" + q, timeout=UPSTREAM_TIMEOUT) as r:
                data = json.loads(r.read().decode("utf-8", "replace"))
            songs = data.get("songs") or []
            if songs:
                dt = int(songs[0].get("dt") or 0)
                if dt > 0:
                    return dt
        except Exception as e:
            log("社区接口取时长失败 id=%s: %s" % (song_id, e))
    try:
        with upstream(META.format(id=song_id), cookie, redirect=True) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        songs = data.get("songs") or []
        if songs:
            return int(songs[0].get("dt") or 0)
    except Exception as e:
        log("旧接口取时长失败 id=%s: %s" % (song_id, e))
    return 0


def _api(path, params, cookie):
    q = urllib.parse.urlencode(params)
    if cookie:
        q += "&cookie=" + urllib.parse.quote(cookie)
    with urllib.request.urlopen(CDR_API + path + "?" + q, timeout=UPSTREAM_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def _pick_title(p):
    name = (p.get("name") or (p.get("mainSong") or {}).get("name") or "").strip()
    dj = ((p.get("dj") or {}).get("nickname") or "").strip()
    if name and dj:
        return "%s - %s" % (name, dj)
    return name or None


def resolve_program(given_id, cookie):
    """播客回退：同一个 @数字 既能点歌也能点播客。
    NetEase 分两层：节目(单集) / 电台(整档)：
      1) /dj/program/detail?id=<id>    它本身就是一集
      2) /dj/program?rid=<id>&limit=1  它是一档电台 -> 取第一集
    拿到后用节目内部 mainSong.id 走普通歌曲流程取地址。
    """
    if not CDR_API:
        return None, None
    try:
        d = _api("/dj/program/detail", {"id": given_id}, cookie)
        p = d.get("program") or d.get("data") or {}
        if isinstance(p, dict) and p.get("mainSong"):
            sid = p["mainSong"].get("id")
            if sid:
                log("识别为播客单集 id=%s -> 歌曲 id=%s" % (given_id, sid))
                return sid, _pick_title(p)
    except Exception as e:
        log("播客单集未命中 id=%s: %s" % (given_id, e))
    try:
        d = _api("/dj/program", {"rid": given_id, "limit": 1, "offset": 0}, cookie)
        ps = d.get("programs") or []
        if ps:
            p = ps[0]
            sid = (p.get("mainSong") or {}).get("id")
            if sid:
                log("识别为播客电台 id=%s -> 第一集歌曲 id=%s" % (given_id, sid))
                return sid, _pick_title(p)
    except Exception as e:
        log("播客电台未命中 id=%s: %s" % (given_id, e))
    return None, None


def resolve(song_id, base_url):
    """返回 (title, url)。url 可能是 CDN 直链，也可能是本机的 /stream 地址。"""
    cookie = load_cookie()
    # ⓪ 播客（电台节目）回退：同一个 @数字 通用，不需要玩家改任何设置
    prog_title = None
    _ps, _pt = resolve_program(song_id, cookie)
    if _ps:
        song_id = str(_ps)
        prog_title = _pt
    now = time.time()
    with _memo_lock:
        hit = _memo.get(song_id)
        if hit and hit[2] > now:
            return hit[0], hit[1]

    # ① 先问本机的社区解析项目（VIP 走这条）
    direct = resolve_via_api(song_id, cookie)
    if direct:
        title = prog_title or fetch_meta(song_id, cookie) or ("网易云 #" + song_id)
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

    title = prog_title or fetch_meta(song_id, cookie) or ("网易云 #" + song_id)
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
            # 只回一句"活着"，不透露任何配置（cookie 有没有、什么模式，都不说）
            self.send_json(200, {"ok": True, "ts": int(time.time())})
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

        if path == "/search":
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            kw = (qs.get("keywords") or [""])[0].strip()
            if not kw:
                self.send_json(400, {"error": "missing keywords"})
                return
            try:
                stype = int((qs.get("type") or ["1"])[0])
            except Exception:
                stype = 1
            try:
                limit = max(1, min(50, int((qs.get("limit") or ["20"])[0])))
            except Exception:
                limit = 20
            try:
                data = _api("/cloudsearch", {"keywords": kw, "type": stype, "limit": limit}, load_cookie())
                trimmed = search_trim(data, stype, limit, "cloudsearch")
                if not trimmed["items"]:
                    # 声音(2000)等在 cloudsearch 上可能为空 → 回退旧版 /search
                    data2 = _api("/search", {"keywords": kw, "type": stype, "limit": limit}, load_cookie())
                    t2 = search_trim(data2, stype, limit, "search")
                    if t2["items"]:
                        trimmed = t2
                    else:
                        trimmed["code2"] = (data2 or {}).get("code")
                        trimmed["keys2"] = sorted(list(((data2 or {}).get("result") or {}).keys()))[:12]
                self.send_json(200, trimmed)
            except Exception as e:
                log("搜索失败 %s: %s" % (kw, e))
                self.send_json(502, {"error": "search failed"})
            return

        if path == "/bili":
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            raw = (qs.get("url") or [""])[0].strip() or (qs.get("bvid") or [""])[0].strip()
            if not raw:
                self.send_json(400, {"error": "missing url"})
                return
            try:
                page_req = int((qs.get("p") or ["0"])[0])
            except Exception:
                page_req = 0
            try:
                payload = bili_payload(raw, self.base_url(), page_req)
            except Exception as e:
                log("B站解析失败 %s: %s" % (raw[:70], e))
                self.send_json(502, {"error": "bili resolve failed: %s" % e})
                return
            log("B站已解析 %s p%s（共 %s 分P）" % (payload["bvid"], payload["page"], payload["pages"]))
            self.send_json(200, payload)
            return

        mbili = re.match(r"^/bili/stream/(BV[0-9A-Za-z]{10})$", path)
        if mbili:
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            try:
                bpage = max(1, int((qs.get("p") or ["1"])[0]))
            except Exception:
                bpage = 1
            self.bili_stream(mbili.group(1), bpage)
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
                self.send_json(200, {"title": title, "url": url,
                                     "durationMs": fetch_dt(song_id, load_cookie())})
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

    def bili_stream(self, bvid, page):
        """把 B 站音频以 mp3 形式发给客户端。

        为什么必须由本服务出口（不能给直链）：
          ① 直链不带 Referer 直接 403 ✗
          ② 直链约 2 小时过期 ✗
          ③ **B 站只给 AAC，而 mod 只认 mp3/ogg/wav/flac** ⇒ 必须转码 ✗
        所以：现场解析直链 → 交给 ffmpeg 转 mp3 → 落缓存 → 从缓存分发（支持 Range）✓
        """
        try:
            _data, pages = bili_meta(bvid, None)
            page = max(1, min(len(pages), page))
            sel = pages[page - 1]
            dur = int(float(sel.get("duration") or 0))
            if BILI_MAX_SEC > 0 and dur > BILI_MAX_SEC:
                self.send_json(413, {"error": "too long (%ds > %ds)" % (dur, BILI_MAX_SEC)})
                return
            path = bili_cache_file(bvid, sel.get("cid"))
        except Exception as e:
            log("B站取流失败 %s p%s: %s" % (bvid, page, e))
            try:
                self.send_json(502, {"error": "bili stream failed: %s" % e})
            except Exception:
                pass
            return

        try:
            size = os.path.getsize(path)
            start, end = 0, size - 1
            code = 200
            rng = self.headers.get("Range")
            if rng:
                m = re.match(r"bytes=(\d*)-(\d*)", rng)
                if m:
                    if m.group(1):
                        start = int(m.group(1))
                    if m.group(2):
                        end = int(m.group(2))
                    end = min(end, size - 1)
                    if start > end or start >= size:
                        start = 0
                    code = 206
            length = end - start + 1
            self.send_response(code)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            if code == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except BrokenPipeError:
            pass
        except Exception as e:
            log("B站分发失败 %s" % e)


def _pick_names(obj, v2_key, v1_key):
    """v2 用 ar/al，v1 用 artists/album —— 两种都认。"""
    v = obj.get(v2_key)
    if not v:
        v = obj.get(v1_key)
    if isinstance(v, dict):
        return v.get("name") or ""
    if isinstance(v, list):
        return "、".join([x.get("name", "") for x in v if isinstance(x, dict) and x.get("name")])
    return ""


def search_trim(data, stype, limit, via):
    """把上游搜索响应裁成客户端要的最小字段集。"""
    res = (data or {}).get("result") or {}
    out = []
    for s in (res.get("songs") or [])[:limit]:
        out.append({"kind": "song", "id": s.get("id"), "name": s.get("name") or "",
                    "artist": _pick_names(s, "ar", "artists"),
                    "album": _pick_names(s, "al", "album"),
                    "durationMs": int(s.get("dt") or s.get("duration") or 0)})
    for p in (res.get("programs") or [])[:limit]:
        dj = p.get("dj") or {}
        ms = p.get("mainSong") or {}
        out.append({"kind": "voice", "id": p.get("id"),
                    "name": p.get("name") or ms.get("name") or "",
                    "artist": dj.get("nickname") or _pick_names(ms, "ar", "artists"),
                    "album": dj.get("brand") or "", "radio": dj.get("name") or "",
                    "radioId": dj.get("id"),
                    "durationMs": int(p.get("duration") or ms.get("dt") or 0)})
    for r in (res.get("radios") or [])[:limit]:
        out.append({"kind": "radio", "id": r.get("id"), "name": r.get("name") or "",
                    "artist": (r.get("dj") or {}).get("nickname") or "", "album": "",
                    "radio": r.get("name") or "", "radioId": r.get("id"), "durationMs": 0})
    for a in (res.get("albums") or [])[:limit]:
        out.append({"kind": "album", "id": a.get("id"), "name": a.get("name") or "",
                    "artist": _pick_names(a, "ar", "artists"), "album": "", "durationMs": 0})
    # 鏂扮増鎼滅储鎺ュ彛锛歿"data": {"resources": [{"resourceId": .., "uiElement": {...}}]}}
    res2 = (data or {}).get("data") or {}
    for r in (res2.get("resources") or [])[:limit]:
        ui = r.get("uiElement") or {}
        mt = (ui.get("mainTitle") or {})
        title = mt.get("title") or ""
        sub = (ui.get("subTitle") or {}).get("title") or ""
        img = (ui.get("image") or {}).get("imageUrl") or ""
        kind = "voice" if stype == 2000 else ("radio" if stype == 1009 else "song")
        if title.startswith("澹伴煶:"):
            title = title.split(":", 1)[1].strip()
        out.append({"kind": kind, "id": int(r.get("resourceId") or 0), "name": title,
                    "artist": sub, "album": "", "cover": img, "durationMs": 0})
    for r in (res.get("djRadios") or [])[:limit]:
        out.append({"kind": "radio", "id": r.get("id"), "name": r.get("name") or "",
                    "artist": (r.get("dj") or {}).get("nickname") or "", "album": "",
                    "radio": r.get("name") or "", "durationMs": 0})
    return {"ok": True, "type": stype, "via": via, "code": (data or {}).get("code"),
            "items": out, "upstreamKeys": sorted(list(res.keys()))[:12]}


# ---------------------------------------------------------------- B 站（视频里的音频）

# 实测（2026-10，本机 CentOS）：
#   · /x/web-interface/view   不需要 cookie、不需要签名 ✓
#   · /x/player/playurl       不需要 cookie、不需要 wbi 签名也能拿到 dash.audio ✓
#   · 但【音频直链必须带 Referer: https://www.bilibili.com/】，不带直接 403 ✗
#   · 而且直链大约 2 小时过期 ⇒ 客户端不能缓存地址，必须由本服务现场解析并转发 ✓
BILI_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
BILI_REF = "https://www.bilibili.com/"
BILI_API = "https://api.bilibili.com"
BILI_BV_RE = re.compile(r"(BV[0-9A-Za-z]{10})")
BILI_AV_RE = re.compile(r"av(\d+)", re.I)
BILI_MEMO_TTL = 240.0     # 直链在内存里记 4 分钟（远小于它的 2 小时有效期，安全）

# B 站只给 AAC（MP4 容器），而 mod 的解码器只认 mp3/ogg/wav/flac ⇒ 必须转码。
# 转一次缓存住：重复播放几乎零成本，多人同时听一首也只转一次 ✓
BILI_CACHE_DIR = os.environ.get("CDR_BILI_CACHE", "/var/cache/clouddisc-resolver/bili")
BILI_CACHE_MB = int(os.environ.get("CDR_BILI_CACHE_MB", "300"))
BILI_MP3_KBPS = os.environ.get("CDR_BILI_KBPS", "192")
BILI_MAX_SEC = int(os.environ.get("CDR_BILI_MAX_SEC", "3600"))
FFMPEG = os.environ.get("CDR_FFMPEG", "ffmpeg")
# 【防止把服务器撑爆】ffmpeg 是唯一的重活，必须限流：
#   · 同时最多 2 个转码（2 核机器，再多就是互相拖慢 + 抢内存）
#   · 每个 ffmpeg 只给 2 个线程、并用 nice 降到最低优先级（宁可慢，不许抢占别的服务）
#   · 排不上队就在 30 秒后明确拒绝（客户端会看到一句人话，而不是干等）
#   · 单首输出上限 120MB（超了说明时长离谱，直接删掉并报错）
BILI_CONCURRENCY = max(1, int(os.environ.get("CDR_BILI_CONCURRENCY", "2")))
BILI_MAX_OUT_MB = int(os.environ.get("CDR_BILI_MAX_OUT_MB", "120"))
_bili_sem = threading.Semaphore(BILI_CONCURRENCY)
_bili_memo = {}
_bili_lock = threading.Lock()


def bili_get(path, params=None):
    url = BILI_API + path
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url)
    req.add_header("User-Agent", BILI_UA)
    req.add_header("Referer", BILI_REF)
    with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def bili_target(raw):
    """把粘贴进来的东西变成 (bvid, aid, 分P)。

    认这些：纯 BV 号、完整视频页 URL（含 ?p=2）、av 号、b23.tv 短链。
    """
    text = (raw or "").strip()
    if not text:
        raise ValueError("empty")
    # 短链：让 urllib 自己跟跳转，拿到最终 /video/BVxxxx 地址
    if "b23.tv" in text:
        req = urllib.request.Request(text if "://" in text else ("https://" + text))
        req.add_header("User-Agent", BILI_UA)
        with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT) as r:
            text = r.geturl()
    page = 1
    m = re.search(r"[?&]p=(\d{1,3})", text)
    if m:
        page = max(1, int(m.group(1)))
    mb = BILI_BV_RE.search(text)
    if mb:
        return mb.group(1), None, page
    ma = BILI_AV_RE.search(text)
    if ma:
        return None, ma.group(1), page
    raise ValueError("no bvid/aid in input")


def bili_meta(bvid=None, aid=None):
    """返回 (view.data, pages)。pages 至少有一条。"""
    params = {"bvid": bvid} if bvid else {"aid": aid}
    d = bili_get("/x/web-interface/view", params)
    if (d or {}).get("code") != 0:
        raise RuntimeError("view code=%s" % (d or {}).get("code"))
    data = d.get("data") or {}
    pages = data.get("pages") or []
    if not pages and data.get("cid"):
        pages = [{"page": 1, "cid": data.get("cid"),
                  "part": data.get("title") or "", "duration": data.get("duration") or 0}]
    if not pages:
        raise RuntimeError("no pages")
    return data, pages


def bili_audio_url(bvid, cid):
    """拿最高码率的【纯音频】DASH 流地址（30280≈192k / 30232≈132k / 30216≈64k）。"""
    key = (bvid, cid)
    now = time.time()
    with _bili_lock:
        hit = _bili_memo.get(key)
        if hit and now - hit[1] < BILI_MEMO_TTL:
            return hit[0]
    d = bili_get("/x/player/playurl",
                 {"bvid": bvid, "cid": cid, "fnval": 16, "fnver": 0, "fourk": 1})
    if (d or {}).get("code") != 0:
        raise RuntimeError("playurl code=%s msg=%s" % ((d or {}).get("code"), (d or {}).get("message")))
    dash = ((d.get("data") or {}).get("dash") or {})
    auds = dash.get("audio") or []
    if not auds:
        # 没有 dash 音频（极少数老视频）—— 不猜、不转 mp4（mod 只能解音频）
        raise RuntimeError("no dash audio")
    best = max(auds, key=lambda a: a.get("bandwidth") or 0)
    url = best.get("baseUrl") or best.get("base_url")
    if not url:
        raise RuntimeError("empty audio url")
    with _bili_lock:
        _bili_memo[key] = (url, now)
    return url


def bili_cache_path(bvid, cid):
    name = "%s_%s.mp3" % (re.sub(r"[^0-9A-Za-z]", "", bvid), cid)
    return os.path.join(BILI_CACHE_DIR, name)


def bili_prune():
    """缓存超过上限就按最久没用的开始删。"""
    try:
        files = []
        for n in os.listdir(BILI_CACHE_DIR):
            if not n.endswith(".mp3"):
                continue
            p = os.path.join(BILI_CACHE_DIR, n)
            try:
                st = os.stat(p)
            except OSError:
                continue
            files.append((st.st_mtime, st.st_size, p))
        total = sum(f[1] for f in files)
        limit = BILI_CACHE_MB * 1024 * 1024
        if total <= limit:
            return
        files.sort()
        for _mt, sz, p in files:
            if total <= limit:
                break
            try:
                os.remove(p)
                total -= sz
            except OSError:
                pass
    except OSError:
        pass


_bili_busy = {}
_bili_busy_lock = threading.Lock()


def bili_cache_file(bvid, cid):
    """确保 (bvid, cid) 的 mp3 已在缓存里，返回本地路径。

    并发保护：同一首被多人同时点，只转一次（其余等锁），避免 2 核机器被同一条 ffmpeg 打满。
    """
    path = bili_cache_path(bvid, cid)
    if os.path.isfile(path) and os.path.getsize(path) > 1024:
        return path
    with _bili_busy_lock:
        lock = _bili_busy.setdefault(path, threading.Lock())
    with lock:
        if os.path.isfile(path) and os.path.getsize(path) > 1024:
            return path
        os.makedirs(BILI_CACHE_DIR, exist_ok=True)
        src = bili_audio_url(bvid, cid)
        tmp = "%s.%d.%d.tmp" % (path, os.getpid(), threading.get_ident())
        # 注意：Referer 必须传给 ffmpeg（它是去拉 B 站直链的那个进程）✗ 少了就 403
        cmd = ["nice", "-n", "10", FFMPEG, "-nostdin", "-loglevel", "error", "-y",
               "-threads", "2",
               "-headers", "Referer: %s\r\nUser-Agent: %s\r\n" % (BILI_REF, BILI_UA),
               "-i", src,
               "-vn", "-c:a", "libmp3lame", "-b:a", "%sk" % BILI_MP3_KBPS,
               "-f", "mp3", tmp]
        # 【限流】同一时刻最多 2 个转码；排不上就明确拒绝，别让请求无限堆着吃内存
        if not _bili_sem.acquire(timeout=30):
            raise RuntimeError("busy: too many concurrent transcodes, retry later")
        try:
            r = subprocess.run(cmd, timeout=300, capture_output=True)
            if r.returncode != 0 or not os.path.isfile(tmp) or os.path.getsize(tmp) < 1024:
                err = (r.stderr or b"").decode("utf-8", "replace")[-300:]
                raise RuntimeError("ffmpeg failed rc=%s %s" % (r.returncode, err))
            out_mb = os.path.getsize(tmp) / (1024.0 * 1024.0)
            if out_mb > BILI_MAX_OUT_MB:
                raise RuntimeError("output too big: %.1fMB > %dMB" % (out_mb, BILI_MAX_OUT_MB))
            os.replace(tmp, path)
        finally:
            _bili_sem.release()
            try:
                if os.path.isfile(tmp):
                    os.remove(tmp)
            except OSError:
                pass
        log("B站已转码 %s cid=%s（%d 字节）" % (bvid, cid, os.path.getsize(path)))
        bili_prune()
        return path


def bili_payload(raw, base_url, page_req):
    """给客户端 /bili 的 JSON：元信息 + 所有分P + 本服务上的取流地址。"""
    bvid, aid, page = bili_target(raw)
    if page_req:
        page = page_req
    data, pages = bili_meta(bvid, aid)
    bv = data.get("bvid") or bvid
    page = max(1, min(len(pages), page))
    sel = pages[page - 1]
    parts = []
    for i, pg in enumerate(pages):
        parts.append({
            "p": i + 1,
            "cid": pg.get("cid"),
            "title": (pg.get("part") or data.get("title") or ""),
            "durationMs": int(float(pg.get("duration") or 0) * 1000),
        })
    dur = int(float(sel.get("duration") or 0) * 1000) or int(float(data.get("duration") or 0) * 1000)
    url = "%s/bili/stream/%s?p=%d&token=%s" % (
        base_url, bv, page, urllib.parse.quote(load_token()))
    return {
        "ok": True,
        "bvid": bv,
        "author": ((data.get("owner") or {}).get("name") or ""),
        "title": data.get("title") or "",
        "page": page,
        "pages": len(pages),
        "durationMs": dur,
        "cid": sel.get("cid"),
        "parts": parts,
        "url": url,
    }


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
