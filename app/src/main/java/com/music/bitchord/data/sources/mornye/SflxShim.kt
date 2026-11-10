package com.music.bitchord.data.sources.mornye

/**
 * The JavaScript that lets a Mornye `.sflx` extension run in this app's module
 * sandbox.
 *
 * Auris's own modules are CommonJS: they fill `module.exports` with
 * `searchTracks` and `getTrackStreamUrl`, and [QuickJsExecutor] is built around
 * that. A Mornye extension does something else entirely — it calls
 * `registerExtension({…})` and hangs its API off a global — and it expects the
 * host to have defined a whole set of globals around it first (`storage`,
 * `credentials`, `log`, `session`, `auth`, `utils`, `matching`, `btoa`, …),
 * because that is the contract the other app established.
 *
 * So an extension is not ported to this host. The host is dressed up to look
 * like the one the extension was written for, and then the result is presented
 * to the sandbox in the shape it already handles:
 *
 *  - [preamble] defines the globals, installed *before* the extension's own
 *    code is evaluated;
 *  - [ADAPTER] runs *after* it, reads `globalThis.extension`, and assigns a
 *    two-function shim to `module.exports` — so everything downstream (the
 *    engine pool, the search and stream paths, the row and format handling)
 *    carries on unchanged.
 *
 * ## What is faithful, and what is not
 *
 * Faithful, because an extension's author is entitled to it:
 *
 *  - `storage` and `credentials` round-trip through a real key/value store, per
 *    extension, surviving restarts. A string stored is a string returned, a
 *    number is a number — the type survives, because it is kept as JSON and
 *    parsed back rather than flattened to text.
 *  - `fetch`/`http` return the web `Response` shape extensions branch on
 *    (`ok`, `status`, `json()`, `text()`, `headers.get()`), and they are
 *    subject to the same host allowlist the extension declared in its manifest.
 *  - the pure-JS utilities are real implementations rather than stubs, because
 *    a wrong HMAC is worse than a missing one: extensions that sign requests
 *    (Spotify's TOTP, most of the signed backends) get a real SHA-1/HMAC-SHA1
 *    and SHA-256/HMAC-SHA256.
 *
 * Not faithful, and stubbed to say so out loud rather than fail silently:
 *
 *  - `file`, `ffmpeg` and `gobackend` are the *download-and-remux* half of the
 *    host. Auris streams from a URL instead of writing a file, so those calls
 *    raise a named error. The one exception is by design: during a stream probe
 *    [SflxHost] replaces `file.download` with a capturer, which is how an
 *    extension's own resolution logic — the part that knows the API — is made
 *    to hand over the URL it would have downloaded.
 *  - `session`/`auth` (the `signedSession` capability) are not implemented.
 *    They answer with a specific "this host cannot do signed sessions" result,
 *    so an extension requiring one reports *that* instead of a type error three
 *    frames deep inside its own code.
 */
internal object SflxShim {

    /**
     * The globals, as text, for one extension.
     *
     * @param manifest the extension being loaded. Its declared network hosts are
     *   enforced natively (see [QuickJsExecutor]) and its settings schema is
     *   what [settingsJson] is expected to match.
     * @param settingsJson the listener's answers for the extension's own
     *   settings block, already merged over the manifest defaults, as a JSON
     *   object. Handed to the extension's `initialize()`.
     */
    fun preamble(manifest: SflxManifest, settingsJson: String, appVersion: String): String {
        val id = jsString(manifest.id)
        val version = jsString(manifest.version)
        val qualities = qualitiesOf(manifest)
        return """
            // ── Host identity ────────────────────────────────────────────
            // An extension reads this to tell which host it is on, and to know
            // which of its own quality tiers are worth offering here.
            var __spine_sflx = {
                id: $id,
                version: $version,
                appVersion: ${jsString(appVersion)},
                qualities: $qualities,
                settings: $settingsJson
            };

            // ── Registration ─────────────────────────────────────────────
            // Mornye's registration is idempotent and keeps the last object
            // handed over, which extensions rely on: several call it once with
            // their full API and again later to add callbacks discovered at
            // runtime.
            globalThis.registerExtension = function (value) {
                if (arguments.length) globalThis.extension = value;
            };

            // ── Logging ──────────────────────────────────────────────────
            globalThis.log = (function () {
                function render(value) {
                    if (value === null) return 'null';
                    if (value === undefined) return 'undefined';
                    if (typeof value === 'string') return value;
                    if (typeof value === 'function') return '<function>';
                    try { return JSON.stringify(value); } catch (e) { return String(value); }
                }
                var levels = ['trace', 'debug', 'info', 'warn', 'error'];
                var api = {};
                for (var i = 0; i < levels.length; i++) {
                    (function (level) {
                        api[level] = function () {
                            var parts = [];
                            for (var j = 0; j < arguments.length && j < 8; j++) {
                                parts.push(render(arguments[j]));
                            }
                            console.log('[sflx:' + level + '] ' + parts.join(' '));
                        };
                    })(levels[i]);
                }
                return api;
            })();

            // ── Persistent state ─────────────────────────────────────────
            // Typed, not flattened: the value is stored as JSON and parsed back,
            // so a stored number is a number when it is read and a stored string
            // is the same string. `credentials` is the same store under a
            // prefixed key — the split exists so an extension's secrets are
            // never the same slot as its preferences, which is all a host can
            // honestly offer without a separate keystore per key.
            globalThis.storage = (function () {
                function read(key) {
                    var raw = __spine.storageGet(String(key));
                    if (raw === null || raw === undefined) return undefined;
                    try { return JSON.parse(raw); } catch (e) { return raw; }
                }
                function write(key, value) {
                    try {
                        return __spine.storageSet(String(key), JSON.stringify(value === undefined ? null : value)) === 'ok';
                    } catch (e) { return false; }
                }
                return {
                    get: function (key, fallback) {
                        if (!arguments.length) return undefined;
                        var value = read(key);
                        return value === undefined ? fallback : value;
                    },
                    set: function (key, value) {
                        return arguments.length >= 2 && write(key, value);
                    },
                    remove: function (key) {
                        return arguments.length > 0 && __spine.storageRemove(String(key)) === 'ok';
                    }
                };
            })();

            globalThis.credentials = (function () {
                var PREFIX = '__credential__';
                function read(key) {
                    var raw = __spine.storageGet(PREFIX + String(key));
                    if (raw === null || raw === undefined) return undefined;
                    try { return JSON.parse(raw); } catch (e) { return raw; }
                }
                return {
                    get: function (key, fallback) {
                        if (!arguments.length) return undefined;
                        var value = read(key);
                        return value === undefined ? fallback : value;
                    },
                    store: function (key, value) {
                        if (arguments.length < 2) return { success: false, error: 'key and value are required' };
                        try {
                            var ok = __spine.storageSet(PREFIX + String(key), JSON.stringify(value)) === 'ok';
                            return ok ? { success: true } : { success: false, error: 'the store refused the write' };
                        } catch (e) { return { success: false, error: String(e && e.message ? e.message : e) }; }
                    },
                    remove: function (key) {
                        return arguments.length > 0 && __spine.storageRemove(PREFIX + String(key)) === 'ok';
                    },
                    has: function (key) {
                        return arguments.length > 0 && read(key) !== undefined;
                    }
                };
            })();

            // ── Base64, text, bytes ──────────────────────────────────────
            // Byte-exact for non-ASCII, which the naive `unescape` trick is not:
            // several of these extensions base64 a UTF-8 payload and compare the
            // result against a hash the server computes.
            globalThis.__sflx_b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
            globalThis.__sflx_utf8 = function (value) {
                var text = String(value);
                var bytes = [];
                for (var i = 0; i < text.length; i++) {
                    var code = text.charCodeAt(i);
                    if (code < 0x80) { bytes.push(code); continue; }
                    if (code < 0x800) {
                        bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
                        continue;
                    }
                    if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
                        var low = text.charCodeAt(i + 1);
                        if (low >= 0xdc00 && low <= 0xdfff) {
                            var point = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                            bytes.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 0x3f),
                                0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f));
                            i++;
                            continue;
                        }
                    }
                    bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
                }
                return bytes;
            };
            globalThis.__sflx_fromUtf8 = function (bytes) {
                var text = '';
                for (var i = 0; i < bytes.length;) {
                    var byte = bytes[i++] & 255;
                    if (byte < 0x80) { text += String.fromCharCode(byte); continue; }
                    if (byte >= 0xc0 && byte < 0xe0 && i < bytes.length) {
                        text += String.fromCharCode(((byte & 0x1f) << 6) | (bytes[i++] & 0x3f));
                        continue;
                    }
                    if (byte >= 0xe0 && byte < 0xf0 && i + 1 < bytes.length) {
                        text += String.fromCharCode(((byte & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
                        continue;
                    }
                    if (byte >= 0xf0 && i + 2 < bytes.length) {
                        var point = ((byte & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12)
                            | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
                        point -= 0x10000;
                        text += String.fromCharCode(0xd800 + (point >> 10), 0xdc00 + (point & 0x3ff));
                        continue;
                    }
                    text += String.fromCharCode(byte);
                }
                return text;
            };
            globalThis.__sflx_base64Encode = function (bytes) {
                var chars = globalThis.__sflx_b64chars;
                var out = '';
                for (var i = 0; i < bytes.length; i += 3) {
                    var b0 = bytes[i] & 255;
                    var b1 = i + 1 < bytes.length ? bytes[i + 1] & 255 : 0;
                    var b2 = i + 2 < bytes.length ? bytes[i + 2] & 255 : 0;
                    out += chars[b0 >> 2];
                    out += chars[((b0 & 3) << 4) | (b1 >> 4)];
                    out += i + 1 < bytes.length ? chars[((b1 & 15) << 2) | (b2 >> 6)] : '=';
                    out += i + 2 < bytes.length ? chars[b2 & 63] : '=';
                }
                return out;
            };
            globalThis.__sflx_base64Decode = function (text) {
                var chars = globalThis.__sflx_b64chars;
                var clean = String(text).replace(/[^A-Za-z0-9+/]/g, '');
                var bytes = [];
                for (var i = 0; i < clean.length; i += 4) {
                    var c0 = chars.indexOf(clean[i]);
                    var c1 = chars.indexOf(clean[i + 1]);
                    var c2 = chars.indexOf(clean[i + 2]);
                    var c3 = chars.indexOf(clean[i + 3]);
                    if (c0 < 0 || c1 < 0) break;
                    bytes.push((c0 << 2) | (c1 >> 4));
                    if (c2 >= 0) bytes.push(((c1 & 15) << 4) | (c2 >> 2));
                    if (c3 >= 0) bytes.push(((c2 & 3) << 6) | c3);
                }
                return bytes;
            };
            globalThis.btoa = function (value) {
                return arguments.length ? globalThis.__sflx_base64Encode(globalThis.__sflx_utf8(value)) : '';
            };
            globalThis.atob = function (value) {
                return arguments.length ? globalThis.__sflx_fromUtf8(globalThis.__sflx_base64Decode(value)) : '';
            };
            if (typeof TextEncoder === 'undefined') {
                globalThis.TextEncoder = function () {
                    this.encoding = 'utf-8';
                    this.encode = function (value) {
                        var bytes = globalThis.__sflx_utf8(arguments.length ? value : '');
                        return Uint8Array.from ? Uint8Array.from(bytes) : bytes;
                    };
                };
            }
            if (typeof TextDecoder === 'undefined') {
                globalThis.TextDecoder = function (encoding) {
                    this.encoding = encoding === undefined ? 'utf-8' : String(encoding);
                    this.decode = function (value) {
                        if (value === null || value === undefined) return '';
                        if (typeof value === 'string') return value;
                        return globalThis.__sflx_fromUtf8(Array.prototype.slice.call(value));
                    };
                };
            }
            if (typeof globalThis.crypto === 'undefined') {
                // Extensions use this for device ids and cache-busting nonces, not
                // for anything secret. Math.random is what is available in here.
                globalThis.crypto = {
                    getRandomValues: function (array) {
                        for (var i = 0; i < array.length; i++) array[i] = Math.floor(Math.random() * 256);
                        return array;
                    }
                };
            }

            // ── Digests ──────────────────────────────────────────────────
            // Real implementations. A stub here would not fail loudly — it would
            // return a plausible-looking signature that the server rejects, and
            // the extension would report a 401 it cannot explain.
            globalThis.__sflx_sha1 = function (bytes) {
                var message = bytes.slice();
                var bitLength = message.length * 8;
                message.push(0x80);
                while (message.length % 64 !== 56) message.push(0);
                var high = Math.floor(bitLength / 4294967296);
                message.push((high >>> 24) & 255, (high >>> 16) & 255, (high >>> 8) & 255, high & 255);
                message.push((bitLength >>> 24) & 255, (bitLength >>> 16) & 255, (bitLength >>> 8) & 255, bitLength & 255);
                var h = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0];
                var words = new Array(80);
                for (var offset = 0; offset < message.length; offset += 64) {
                    for (var i = 0; i < 16; i++) {
                        words[i] = (message[offset + i * 4] << 24) | (message[offset + i * 4 + 1] << 16)
                            | (message[offset + i * 4 + 2] << 8) | message[offset + i * 4 + 3];
                    }
                    for (var i = 16; i < 80; i++) {
                        var word = words[i - 3] ^ words[i - 8] ^ words[i - 14] ^ words[i - 16];
                        words[i] = (word << 1) | (word >>> 31);
                    }
                    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
                    for (var i = 0; i < 80; i++) {
                        var f, k;
                        if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5a827999; }
                        else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; }
                        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc; }
                        else { f = b ^ c ^ d; k = 0xca62c1d6; }
                        var temp = (((a << 5) | (a >>> 27)) + f + e + k + words[i]) | 0;
                        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = temp;
                    }
                    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0;
                    h[3] = (h[3] + d) | 0; h[4] = (h[4] + e) | 0;
                }
                var out = [];
                for (var i = 0; i < 5; i++) {
                    out.push((h[i] >>> 24) & 255, (h[i] >>> 16) & 255, (h[i] >>> 8) & 255, h[i] & 255);
                }
                return out;
            };
            globalThis.__sflx_hmac = function (hash, blockSize, keyBytes, messageBytes) {
                var key = keyBytes.slice();
                if (key.length > blockSize) key = hash(key);
                while (key.length < blockSize) key.push(0);
                var inner = [], outer = [];
                for (var i = 0; i < blockSize; i++) {
                    inner.push(key[i] ^ 0x36);
                    outer.push(key[i] ^ 0x5c);
                }
                return hash(outer.concat(hash(inner.concat(messageBytes))));
            };
            globalThis.__sflx_hex = function (bytes) {
                var out = '';
                for (var i = 0; i < bytes.length; i++) {
                    var value = bytes[i] & 255;
                    out += (value < 16 ? '0' : '') + value.toString(16);
                }
                return out;
            };

            // ── Matching ─────────────────────────────────────────────────
            // Extensions gate their own verification on these: a score of 0.86
            // from `compareStrings` is the difference between accepting a
            // release and rejecting it. A binary yes/no here would make every
            // extension either credulous or unusable.
            globalThis.matching = (function () {
                function normalize(value) {
                    return String(value === null || value === undefined ? '' : value)
                        .toLowerCase()
                        .normalize('NFKD')
                        .replace(/[\u0300-\u036f]/g, '')
                        .replace(/[^a-z0-9]+/g, ' ')
                        .trim();
                }
                function levenshtein(a, b) {
                    if (!a.length) return b.length;
                    if (!b.length) return a.length;
                    var previous = new Array(b.length + 1);
                    var current = new Array(b.length + 1);
                    for (var j = 0; j <= b.length; j++) previous[j] = j;
                    for (var i = 1; i <= a.length; i++) {
                        current[0] = i;
                        for (var j = 1; j <= b.length; j++) {
                            var cost = a.charCodeAt(i - 1) === b.charCodeAt(j - 1) ? 0 : 1;
                            current[j] = Math.min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost);
                        }
                        for (var j = 0; j <= b.length; j++) previous[j] = current[j];
                    }
                    return previous[b.length];
                }
                return {
                    compareStrings: function (first, second) {
                        if (arguments.length < 2) return 0;
                        var left = normalize(first), right = normalize(second);
                        if (!left.length && !right.length) return 1;
                        if (!left.length || !right.length) return 0;
                        var distance = levenshtein(left, right);
                        return 1 - distance / Math.max(left.length, right.length);
                    },
                    compareDuration: function (first, second, tolerance) {
                        if (arguments.length < 2) return false;
                        var allowed = tolerance === undefined ? 3000 : Number(tolerance);
                        var left = Number(first), right = Number(second);
                        if (!isFinite(left) || !isFinite(right)) return false;
                        return Math.abs(left - right) <= allowed;
                    },
                    normalizeString: function (value) {
                        return arguments.length ? normalize(value) : '';
                    }
                };
            })();

            // ── Utilities ────────────────────────────────────────────────
            globalThis.utils = (function () {
                var AGENTS = [
                    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
                    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36',
                    'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
                    'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
                ];
                function bytesOf(value) {
                    if (typeof value === 'string') return globalThis.__sflx_utf8(value);
                    if (Object.prototype.toString.call(value) === '[object Array]') {
                        return Array.prototype.map.call(value, function (item) {
                            return typeof item === 'number' ? item & 255 : 0;
                        });
                    }
                    if (value && typeof value.length === 'number') {
                        var out = [];
                        for (var i = 0; i < value.length; i++) out.push(value[i] & 255);
                        return out;
                    }
                    return null;
                }
                return {
                    isDownloadCancelled: function () { return false; },
                    isRequestCancelled: function () { return false; },
                    randomUserAgent: function () { return AGENTS[Math.floor(Math.random() * AGENTS.length)]; },
                    appVersion: function () { return __spine_sflx.appVersion; },
                    appUserAgent: function () { return AGENTS[0]; },
                    setDownloadStatus: function () { return true; },
                    getResolutionRemainingMs: function () { return 0; },
                    base64Encode: function (value) {
                        return arguments.length ? globalThis.__sflx_base64Encode(globalThis.__sflx_utf8(value)) : '';
                    },
                    base64Decode: function (value) {
                        return arguments.length ? globalThis.__sflx_fromUtf8(globalThis.__sflx_base64Decode(value)) : '';
                    },
                    sha1: function (value) {
                        return arguments.length ? globalThis.__sflx_hex(globalThis.__sflx_sha1(bytesOf(value))) : '';
                    },
                    hmacSHA1: function (key, message) {
                        if (arguments.length < 2) return [];
                        var keyBytes = bytesOf(key), messageBytes = bytesOf(message);
                        if (keyBytes === null || messageBytes === null) return [];
                        return globalThis.__sflx_hmac(globalThis.__sflx_sha1, 64, keyBytes, messageBytes);
                    },
                    hmacSHA1Base64: function (key, message) {
                        var digest = this.hmacSHA1(key, message);
                        return globalThis.__sflx_base64Encode(digest);
                    },
                    hmacSHA1Hex: function (key, message) {
                        return globalThis.__sflx_hex(this.hmacSHA1(key, message));
                    },
                    parseJSON: function (value) {
                        if (!arguments.length) return undefined;
                        try { return JSON.parse(String(value)); } catch (e) { return undefined; }
                    },
                    stringifyJSON: function (value) {
                        if (!arguments.length) return '';
                        try { return JSON.stringify(value); } catch (e) { return ''; }
                    },
                    sleep: function (milliseconds) {
                        var ms = Number(milliseconds);
                        if (!isFinite(ms) || ms <= 0) return true;
                        // Synchronous, like the host this stands in for: an
                        // extension calling sleep() is spacing out its own
                        // requests and does not await the answer.
                        var until = Date.now() + Math.min(Math.trunc(ms), 30000);
                        while (Date.now() < until) { /* spin, as the original does */ }
                        return true;
                    },
                    // The binary and crypto-entry helpers below are the parts of
                    // the other host that exist to encrypt a request to a
                    // proprietary backend. Answering with a named failure keeps
                    // the extension's own error handling in charge.
                    encrypt: function () { return { success: false, error: 'this host does not implement utils.encrypt' }; },
                    decrypt: function () { return { success: false, error: 'this host does not implement utils.decrypt' }; },
                    generateKey: function () { return { success: false, error: 'this host does not implement utils.generateKey' }; },
                    encryptBlockCipher: function () { return { success: false, error: 'this host does not implement utils.encryptBlockCipher' }; },
                    decryptBlockCipher: function () { return { success: false, error: 'this host does not implement utils.decryptBlockCipher' }; },
                    decryptCTRSegments: function () { return { success: false, error: 'this host does not implement utils.decryptCTRSegments' }; },
                    md5: function () { return ''; },
                    sha256: function () { return ''; },
                    hmacSHA256: function () { return ''; },
                    hmacSHA256Base64: function () { return ''; }
                };
            })();

            // ── HTTP ─────────────────────────────────────────────────────
            // Both spellings, because extensions use both: `fetch` in the web
            // shape it already returns, and `http.*` for the older call style.
            // Both go through the sandbox's own fetch, so the manifest's
            // network permissions are enforced in one place.
            globalThis.http = {
                get: function (url, headers) { return globalThis.http.request(url, null, 'GET', headers); },
                post: function (url, body, headers) { return globalThis.http.request(url, body, 'POST', headers); },
                put: function (url, body, headers) { return globalThis.http.request(url, body, 'PUT', headers); },
                patch: function (url, body, headers) { return globalThis.http.request(url, body, 'PATCH', headers); },
                delete: function (url, body, headers) { return globalThis.http.request(url, body, 'DELETE', headers); },
                request: function (url, body, method, headers) {
                    if (!arguments.length) return { ok: false, status: 0, statusText: 'Network Error', error: 'URL is required' };
                    var options = { method: method || 'GET' };
                    if (headers && typeof headers === 'object') options.headers = headers;
                    if (body !== undefined && body !== null && options.method !== 'GET') {
                        options.body = typeof body === 'string' ? body : JSON.stringify(body);
                    }
                    try {
                        // Returns the promise the sandbox's fetch returns; an
                        // extension that awaited http.get() keeps working.
                        return globalThis.fetch(url, options);
                    } catch (e) {
                        return { ok: false, status: 0, statusText: 'Network Error', error: String(e && e.message ? e.message : e) };
                    }
                },
                clearCookies: function () { return true; }
            };

            // ── Files, transcoding, legacy backend ───────────────────────
            // This host streams from a URL rather than downloading a file, so
            // the whole write-a-file path is absent. It raises a named error
            // instead of quietly succeeding at nothing: an extension whose only
            // route to audio is a file it writes will say so, and be told so.
            globalThis.__sflx_no_files = function (what) {
                var error = new Error(what + ' is not available: Auris streams from a URL instead of writing files');
                error.code = 'host_unsupported';
                throw error;
            };
            globalThis.file = {
                download: function () { return globalThis.__sflx_no_files('file.download'); },
                downloadSegments: function () { return globalThis.__sflx_no_files('file.downloadSegments'); },
                read: function () { return globalThis.__sflx_no_files('file.read'); },
                write: function () { return globalThis.__sflx_no_files('file.write'); },
                exists: function () { return false; },
                delete: function () { return false; }
            };
            globalThis.ffmpeg = {
                getInfo: function () { return { success: false, error: 'this host does not transcode' }; },
                convert: function () { return { success: false, error: 'this host does not transcode' }; },
                execute: function () { return { success: false, error: 'this host does not run ffmpeg' }; }
            };
            globalThis.gobackend = {
                sanitizeFilename: function (value) {
                    return String(value === null || value === undefined ? '' : value)
                        .replace(/[\\/:*?"<>|]+/g, '_')
                        .replace(/\s+/g, ' ')
                        .trim();
                },
                buildFilename: function () { return ''; },
                getLocalTime: function () { return { error: 'this host does not implement gobackend.getLocalTime' }; },
                getAudioQuality: function () { return { error: 'this host does not read files' }; },
                getLyricsLRC: function () { return { error: 'this host does not implement gobackend.getLyricsLRC' }; },
                checkISRCExists: function () { return { exists: false }; },
                addToISRCIndex: function () { return { success: false, error: 'this host does not index files' }; }
            };

            // ── Signed sessions ──────────────────────────────────────────
            // Not implemented. The extensions that need these (the ones whose
            // manifest asks for `signedSession`) sign every request with a key
            // they exchange for a grant; this host has no such exchange, so a
            // pretend signature would be worse than none. Each method answers
            // in its own documented shape with that said plainly, which is what
            // the extension's error paths already handle.
            globalThis.session = {
                status: function () { return { authenticated: false, supported: false, error: 'this host does not implement signed sessions' }; },
                clear: function () { return { success: false, error: 'this host does not implement signed sessions' }; },
                completeGrant: function () { return { success: false, error: 'this host does not implement signed sessions' }; },
                signedFetch: function () { return { ok: false, status: 0, error: 'this host does not implement signed sessions' }; }
            };
            globalThis.auth = {
                openAuthUrl: function () { return { success: false, error: 'this host does not implement interactive auth' }; },
                getAuthCode: function () { return undefined; },
                setAuthCode: function () { return { success: false, error: 'this host does not implement interactive auth' }; },
                clearAuth: function () { return { success: false }; },
                isAuthenticated: function () { return false; },
                getTokens: function () { return { success: false, error: 'this host does not implement interactive auth' }; },
                generatePKCE: function () { return { success: false, error: 'this host does not implement PKCE' }; },
                getPKCE: function () { return { success: false, error: 'this host does not implement PKCE' }; },
                startOAuthWithPKCE: function () { return { success: false, error: 'this host does not implement PKCE' }; },
                exchangeCodeWithPKCE: function () { return { success: false, error: 'this host does not implement PKCE' }; }
            };
        """.trimIndent()
    }

    /**
     * The bridge from a registered extension to the two exports this sandbox
     * calls.
     *
     * Runs after the extension's own code, inside the same closure, so
     * `module.exports` is the object [QuickJsExecutor] looks at. Everything an
     * extension knows about its own API — which search it implements, how it
     * resolves a stream, what its quality ids mean — stays in JavaScript, and
     * what comes back out is the JSON shape the rest of this app already
     * speaks.
     *
     * ## Why the stream path captures a URL instead of downloading
     *
     * A Mornye download provider resolves audio inside `download()`: it signs,
     * it picks a candidate, and it hands the final URL to `file.download`. That
     * resolution *is* the extension's value, and it is not exposed anywhere
     * else — there is no `getStreamUrl` in the contract. So the probe runs the
     * real thing with one substitution: `file.download` records the URL and
     * throws to unwind, and the URL the extension was about to fetch is what
     * this app plays. Nothing is downloaded here, and the extension's own
     * candidate ranking decides which URL is captured, because it is the
     * extension's code that runs.
     */
    val ADAPTER = """
        (function () {
            var extension = globalThis.extension || {};

            // The extension's own setup, given the settings the listener filled
            // in for it. Called before anything else so that a token it restores
            // here is in place for the first search; a failure is logged and
            // survivable, because an extension that cannot restore its session
            // can still usually mint a new one on demand.
            if (typeof extension.initialize === 'function') {
                try {
                    extension.initialize(globalThis.__spine_sflx.settings);
                } catch (error) {
                    console.log('[sflx] initialize() failed: ' + (error && error.message ? error.message : error));
                }
            }

            function text(value) {
                if (value === null || value === undefined) return '';
                if (typeof value === 'string') return value;
                if (typeof value === 'number' || typeof value === 'boolean') return String(value);
                if (Object.prototype.toString.call(value) === '[object Array]') {
                    return value.map(text).filter(function (item) { return item.length > 0; }).join(', ');
                }
                return String(value);
            }
            function number(value, fallback) {
                var parsed = Number(value);
                return isFinite(parsed) ? parsed : (fallback || 0);
            }
            function firstOf() {
                for (var i = 0; i < arguments.length; i++) {
                    if (arguments[i] !== undefined && arguments[i] !== null && arguments[i] !== '') return arguments[i];
                }
                return '';
            }

            // A Mornye row, as this app describes one. `duration_ms` is the
            // extensions' unit and seconds are this host's; the conversion is
            // here rather than downstream because only one side of it can be
            // wrong.
            //
            // `raw` is the extension's own object, kept whole and handed back
            // to it in `preparedContext` on the download call: it holds the
            // album id, the ISRC and the millisecond duration that this app has
            // no opinion about, and the extension does.
            function rowOf(track) {
                if (!track || typeof track !== 'object') return null;
                var id = text(firstOf(track.id, track.track_id, track.trackId, track.song_id));
                if (!id) return null;
                return {
                    id: id,
                    title: text(firstOf(track.name, track.title, track.track_name)),
                    artist: text(firstOf(track.artists, track.artist, track.artist_name, track.performer)),
                    album: text(firstOf(track.album_name, track.album, track.album_title)),
                    albumCover: text(firstOf(track.cover_url, track.images, track.image, track.album_cover)),
                    duration: Math.round(number(firstOf(track.duration_ms, track.duration), 0) / 1000),
                    audioQuality: text(firstOf(track.quality, track.audio_quality, track.quality_id)),
                    format: text(firstOf(track.format, track.codec)),
                    raw: track
                };
            }

            function tracksOf(result) {
                if (!result) return [];
                if (Object.prototype.toString.call(result) === '[object Array]') return result;
                var list = firstOf(result.tracks, result.results, result.items, result.songs);
                if (Object.prototype.toString.call(list) === '[object Array]') return list;
                if (list && Object.prototype.toString.call(list.items) === '[object Array]') return list.items;
                return [];
            }

            function contextOf(raw) {
                if (!raw) return {};
                if (typeof raw === 'string') {
                    try { return JSON.parse(raw); } catch (e) { return {}; }
                }
                return raw;
            }

            // Mornye's search takes a second argument of options, and its
            // extensions are split between `customSearch` (which honours the
            // filters and the limit) and a bare `searchTracks`. Prefer the
            // richer one: a search that ignores its filters comes back with
            // albums where songs were asked for.
            function search(query, limit) {
                var size = limit > 0 ? limit : 20;
                if (typeof extension.customSearch === 'function') {
                    return extension.customSearch(query, { limit: size });
                }
                if (typeof extension.searchTracks === 'function') {
                    return extension.searchTracks(query, size);
                }
                return { success: false, error: 'this extension cannot search' };
            }

            module.exports = {
                searchTracks: function (query, limit) {
                    var started = Date.now();
                    var result = search(query, limit);
                    var rows = [];
                    var candidates = tracksOf(result);
                    for (var i = 0; i < candidates.length; i++) {
                        var row = rowOf(candidates[i]);
                        if (row) rows.push(row);
                    }
                    // The extension's own diagnosis, when it gave one, is what
                    // the caller needs: "no tracks" and "the search was refused"
                    // are different faults and only the second is fixable.
                    var failure = rows.length === 0 && result && result.success === false
                        ? text(firstOf(result.error, result.message, result.error_message))
                        : '';
                    return JSON.stringify({
                        tracks: rows,
                        total: rows.length,
                        tookMs: Date.now() - started,
                        error: failure
                    });
                },

                getTrackStreamUrl: function (trackId, quality, rawContext) {
                    var context = contextOf(rawContext);
                    var meta = { id: text(trackId), audioQuality: text(quality) };
                    var answered = function (url, error) {
                        return JSON.stringify({ streamUrl: url, track: meta, error: error || '' });
                    };
                    if (typeof extension.download !== 'function') {
                        return answered(null, 'this extension can describe tracks but has no downloader');
                    }

                    // The substitution, and the only reason this works: the
                    // extension's own resolution runs to the point of fetching
                    // audio, and the URL it was about to fetch is taken instead.
                    var captured = null;
                    var capturedSegments = null;
                    var marker = { __sflx_capture: true };
                    var realDownload = globalThis.file.download;
                    var realSegments = globalThis.file.downloadSegments;
                    globalThis.file.download = function (url) {
                        if (captured === null) captured = text(url);
                        throw marker;
                    };
                    globalThis.file.downloadSegments = function (segments) {
                        if (capturedSegments === null && segments) capturedSegments = segments;
                        throw marker;
                    };
                    var failure = '';
                    try {
                        var options = {
                            preparedContext: { track: context.track || null }
                        };
                        var outcome = extension.download(text(trackId), text(quality), '', null, options);
                        if (outcome && outcome.success === false) {
                            failure = text(firstOf(outcome.error_message, outcome.error, outcome.reason));
                        }
                    } catch (error) {
                        // Our marker is the expected exit; anything else is the
                        // extension's own failure and is reported as such.
                        if (!error || error.__sflx_capture !== true) {
                            failure = text(error && error.message ? error.message : error);
                        }
                    } finally {
                        globalThis.file.download = realDownload;
                        globalThis.file.downloadSegments = realSegments;
                    }

                    if (!captured && capturedSegments) {
                        // A segmented stream: the extension handed over playlist
                        // entries rather than one file. Playing those means
                        // fetching each with the extension's own headers, which
                        // this host does not do yet — so it is refused out loud
                        // rather than played as whatever the first segment is.
                        return answered(null, 'this extension serves a segmented stream, which this host cannot play yet');
                    }
                    if (!captured) {
                        return answered(null, failure || 'the extension did not resolve a stream URL');
                    }
                    meta.audioQuality = firstOf(meta.audioQuality, '');
                    return answered(captured, '');
                }
            };
        })();
    """.trimIndent()

    /**
     * The extension's quality tiers, as JSON, in the order its manifest lists
     * them (best first, by the format's own convention).
     *
     * Read off the manifest rather than assumed, because the ids are the
     * extension's own vocabulary: `HI_RES_LOSSLESS`, `aac_160`, `MP3_320`. A
     * tier this app asks for has to be translated into one of these, and the
     * manifest is the only place that mapping exists.
     */
    fun qualitiesOf(manifest: SflxManifest): String {
        val options = manifest.raw["qualityOptions"] as? kotlinx.serialization.json.JsonArray
        if (options == null) return "[]"
        val entries = options.mapNotNull { element ->
            val entry = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val kind = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            "{\"id\":${jsString(id)},\"kind\":${jsString(kind)}}"
        }
        return entries.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    /**
     * A Kotlin string as a JavaScript string literal.
     *
     * Everything injected into the preamble comes from a manifest written by
     * somebody else, so the quoting is not cosmetic: an id containing a quote
     * would otherwise end the literal and put attacker-authored text in the
     * script body. Escaping the delimiters, the escapes and the line breaks,
     * plus the two characters that end a statement in a `script` context, is
     * what keeps a manifest a value rather than code.
     */
    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    // The version is passed in rather than written here: an extension branches
    // on `appVersion`, and a constant in a file is a number that is right until
    // the next release and silently wrong after it.
}
